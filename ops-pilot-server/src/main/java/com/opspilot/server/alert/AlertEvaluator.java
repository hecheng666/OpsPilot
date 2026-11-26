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
import java.util.HashMap;
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

    /** 告警阈值：触发与自动恢复共用同一组常量，保证判定对称 */
    private static final double P95_THRESHOLD_MS = 800;
    private static final double ERROR_RATE_THRESHOLD_PCT = 5;
    private static final double THREAD_POOL_RATIO_THRESHOLD = 0.8;
    private static final double CACHE_HIT_THRESHOLD_PCT = 60;
    private static final double DB_RT_THRESHOLD_MS = 500;

    /** 对应指标连续多少个评估窗口（10s/次）正常后自动关闭事故 */
    static final int AUTO_RESOLVE_WINDOWS = 3;

    private final MetricPointMapper metricPointMapper;
    private final IncidentMapper incidentMapper;
    private final MetricCollector metricCollector;

    @Value("${opspilot.service-name}")
    private String serviceName;

    /** 冷启动预热期：缓存逐步填充，命中率天然偏低，此窗口内不评估缓存命中率 */
    @Value("${opspilot.alert-warmup-seconds:180}")
    private long warmupSeconds;

    private final long startedAtNanos = System.nanoTime();

    /** 事故 id → 连续正常窗口数（内存态，事故关闭后清理） */
    private final Map<Long, Integer> healthyWindows = new HashMap<>();

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

        if (p95 > P95_THRESHOLD_MS) {
            fireOrMerge("P95 延迟超过阈值 800ms（当前 " + fmt(p95) + "ms）", "P2", "P95_LATENCY_HIGH");
        }
        if (errorRate > ERROR_RATE_THRESHOLD_PCT) {
            fireOrMerge("错误率超过阈值 5%（当前 " + fmt(errorRate) + "%）", "P2", "ERROR_RATE_HIGH");
        }
        if (threadMax > 0 && threadActive / threadMax > THREAD_POOL_RATIO_THRESHOLD) {
            fireOrMerge("订单线程池使用率超过 80%（active=" + (int) threadActive + "/" + (int) threadMax + "）", "P2", "THREAD_POOL_SATURATED");
        }
        // 冷启动预热期内缓存尚未填充，命中率偏低属正常现象，不触发告警
        if (!inWarmup() && cacheHitRate > 0 && cacheHitRate < CACHE_HIT_THRESHOLD_PCT) {
            fireOrMerge("缓存命中率低于 60%（当前 " + fmt(cacheHitRate) + "%）", "P2", "CACHE_HIT_LOW");
        }
        if (dbRt > DB_RT_THRESHOLD_MS) {
            fireOrMerge("DB 平均 RT 超过 500ms（当前 " + fmt(dbRt) + "ms）", "P2", "DB_RT_HIGH");
        }

        // 故障恢复后自动关闭：对应指标连续 N 个窗口正常
        autoResolve(p95, errorRate, threadActive, threadMax, cacheHitRate, dbRt);
    }

    /** 遍历 OPEN 事故，对应指标连续 AUTO_RESOLVE_WINDOWS 个窗口正常则自动关闭 */
    private void autoResolve(double p95, double errorRate, double threadActive,
                             double threadMax, double cacheHitRate, double dbRt) {
        List<Incident> open = incidentMapper.selectList(new QueryWrapper<Incident>()
                .eq("service", serviceName).eq("status", "OPEN"));
        if (open.isEmpty()) {
            healthyWindows.clear();
            return;
        }
        boolean serviceDown = metricCollector.isServiceDown();
        for (Incident incident : open) {
            boolean healthy = isHealthy(incident.getTitle(), serviceDown,
                    p95, errorRate, threadActive, threadMax, cacheHitRate, dbRt);
            if (!healthy) {
                healthyWindows.put(incident.getId(), 0);
                continue;
            }
            int count = healthyWindows.merge(incident.getId(), 1, Integer::sum);
            if (count >= AUTO_RESOLVE_WINDOWS) {
                incident.setStatus("CLOSED");
                incident.setResolvedAt(LocalDateTime.now());
                incidentMapper.updateById(incident);
                healthyWindows.remove(incident.getId());
                log.info("事故 #{} 对应指标连续 {} 个窗口正常，自动关闭", incident.getId(), count);
            }
        }
        var openIds = open.stream().map(Incident::getId).collect(Collectors.toSet());
        healthyWindows.keySet().retainAll(openIds);
    }

    /** 按事故标题特征判断对应指标是否已恢复正常（与告警阈值对称） */
    static boolean isHealthy(String title, boolean serviceDown, double p95, double errorRate,
                             double threadActive, double threadMax, double cacheHitRate, double dbRt) {
        switch (signatureOf(title)) {
            case "SERVICE_DOWN":
                return !serviceDown;
            case "P95_LATENCY_HIGH":
                return p95 <= P95_THRESHOLD_MS;
            case "ERROR_RATE_HIGH":
                return errorRate <= ERROR_RATE_THRESHOLD_PCT;
            case "THREAD_POOL_SATURATED":
                return threadMax > 0 && threadActive / threadMax <= THREAD_POOL_RATIO_THRESHOLD;
            case "CACHE_HIT_LOW":
                return cacheHitRate >= CACHE_HIT_THRESHOLD_PCT;
            case "DB_RT_HIGH":
                return dbRt <= DB_RT_THRESHOLD_MS;
            default:
                return false;
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
    static String signatureOf(String title) {
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
