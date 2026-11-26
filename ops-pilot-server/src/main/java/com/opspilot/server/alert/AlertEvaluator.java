package com.opspilot.server.alert;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.collector.MetricCollector;
import com.opspilot.server.entity.Incident;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.mapper.IncidentMapper;
import com.opspilot.server.mapper.MetricPointMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 阈值告警评估器：每 10s 评估最近 1 分钟窗口。
 * 同服务同标题 10 分钟内合并为同一事故。
 */
@Component
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final MetricPointMapper metricPointMapper;
    private final IncidentMapper incidentMapper;
    private final MetricCollector metricCollector;

    @Value("${opspilot.service-name}")
    private String serviceName;

    /** 冷启动预热期：缓存逐步填充，命中率天然偏低，此窗口内不评估缓存命中率 */
    @Value("${opspilot.alert-warmup-seconds:180}")
    private long warmupSeconds;

    private final long startedAtNanos = System.nanoTime();

    public AlertEvaluator(MetricPointMapper metricPointMapper, IncidentMapper incidentMapper,
                          MetricCollector metricCollector) {
        this.metricPointMapper = metricPointMapper;
        this.incidentMapper = incidentMapper;
        this.metricCollector = metricCollector;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void evaluate() {
        // 服务 DOWN 告警
        if (metricCollector.isServiceDown()) {
            fireOrMerge("服务不可用：连续 3 次采集失败", "P1", null);
            return;
        }

        LocalDateTime since = LocalDateTime.now().minusMinutes(1);
        Map<String, List<MetricPoint>> byMetric = metricPointMapper.selectList(
                        new QueryWrapper<MetricPoint>()
                                .eq("service", serviceName)
                                .ge("ts", since))
                .stream().collect(Collectors.groupingBy(MetricPoint::getMetric));

        double p95 = max(byMetric, "p95");
        double errorRate = max(byMetric, "errorRate");
        double threadActive = max(byMetric, "threadActive");
        double threadMax = max(byMetric, "threadMax");
        double cacheHitRate = min(byMetric, "cacheHitRate");
        double dbRt = max(byMetric, "dbRtMs");

        if (p95 > 800) {
            fireOrMerge("P95 延迟超过阈值 800ms（当前 " + fmt(p95) + "ms）", "P2", "P95_LATENCY_HIGH");
        }
        if (errorRate > 5) {
            fireOrMerge("错误率超过阈值 5%（当前 " + fmt(errorRate) + "%）", "P2", "ERROR_RATE_HIGH");
        }
        if (threadMax > 0 && threadActive / threadMax > 0.8) {
            fireOrMerge("订单线程池使用率超过 80%（active=" + (int) threadActive + "/" + (int) threadMax + "）", "P2", "THREAD_POOL_SATURATED");
        }
        // 冷启动预热期内缓存尚未填充，命中率偏低属正常现象，不触发告警
        if (!inWarmup() && cacheHitRate > 0 && cacheHitRate < 60) {
            fireOrMerge("缓存命中率低于 60%（当前 " + fmt(cacheHitRate) + "%）", "P2", "CACHE_HIT_LOW");
        }
        if (dbRt > 500) {
            fireOrMerge("DB 平均 RT 超过 500ms（当前 " + fmt(dbRt) + "ms）", "P2", "DB_RT_HIGH");
        }
    }

    private void fireOrMerge(String titleWithValue, String severity, String signatureKey) {
        // 合并窗口：10 分钟内同服务同特征
        String signature = signatureKey == null ? "SERVICE_DOWN" : signatureKey;
        LocalDateTime mergeSince = LocalDateTime.now().minusMinutes(10);

        List<Incident> open = incidentMapper.selectList(new QueryWrapper<Incident>()
                .eq("service", serviceName).eq("status", "OPEN").ge("created_at", mergeSince));
        boolean exists = open.stream().anyMatch(i -> Objects.equals(signatureOf(i.getTitle()), signature));
        if (exists) {
            return;
        }
        Incident incident = new Incident();
        incident.setService(serviceName);
        incident.setTitle(titleWithValue);
        incident.setSeverity(severity);
        incident.setStatus("OPEN");
        incident.setStartedAt(LocalDateTime.now());
        incident.setCreatedAt(LocalDateTime.now());
        incidentMapper.insert(incident);
        log.info("创建事故 #{}: {}", incident.getId(), titleWithValue);
    }

    private boolean inWarmup() {
        return System.nanoTime() - startedAtNanos < warmupSeconds * 1_000_000_000L;
    }

    /** 从标题中提取稳定特征（去掉括号内的动态值） */
    private String signatureOf(String title) {
        if (title.contains("P95")) return "P95_LATENCY_HIGH";
        if (title.contains("错误率")) return "ERROR_RATE_HIGH";
        if (title.contains("线程池")) return "THREAD_POOL_SATURATED";
        if (title.contains("缓存命中率")) return "CACHE_HIT_LOW";
        if (title.contains("DB")) return "DB_RT_HIGH";
        return "SERVICE_DOWN";
    }

    private double max(Map<String, List<MetricPoint>> byMetric, String key) {
        return byMetric.getOrDefault(key, List.of()).stream()
                .mapToDouble(MetricPoint::getValue).max().orElse(0);
    }

    private double min(Map<String, List<MetricPoint>> byMetric, String key) {
        return byMetric.getOrDefault(key, List.of()).stream()
                .mapToDouble(MetricPoint::getValue).min().orElse(0);
    }

    private String fmt(double v) {
        return String.valueOf(Math.round(v * 100) / 100.0);
    }
}
