package com.opspilot.server.eval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.entity.LogEntry;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.mapper.LogEntryMapper;
import com.opspilot.server.mapper.MetricPointMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 症状分析器：只依据已采集的指标与日志做阈值/关键词推断，不读取故障真值。
 * 统计口径为窗口内「峰值/谷值」而非单点最新值，避免采样噪声导致漏检。
 * 供 RULE 基线与 Agent 降级模式共用，保证降级路径的可信度。
 */
@Component
public class SymptomAnalyzer {

    private final MetricPointMapper metricPointMapper;
    private final LogEntryMapper logEntryMapper;

    @Value("${opspilot.service-name}")
    private String serviceName;

    public SymptomAnalyzer(MetricPointMapper metricPointMapper, LogEntryMapper logEntryMapper) {
        this.metricPointMapper = metricPointMapper;
        this.logEntryMapper = logEntryMapper;
    }

    /** 分析快照 */
    public record Snapshot(Map<String, Double> metrics, List<String> triggers, List<String> logPatterns,
                           String causeTitle, String summary, String suggestedAction, List<String> sources) {}

    public Snapshot analyze(int windowMinutes) {
        return analyze(LocalDateTime.now().minusMinutes(windowMinutes));
    }

    /** 从 since 起统计症状（评测时传注入时刻，隔离上一场景数据） */
    public Snapshot analyze(LocalDateTime since) {
        double p95 = maxOf("p95", since);
        double dbRt = maxOf("dbRtMs", since);
        double cacheHit = minPositiveOf("cacheHitRate", since);
        double threadActive = maxOf("threadActive", since);
        double threadMax = maxOf("threadMax", since);
        double downstreamRt = maxOf("downstreamRtMs", since);
        double errorRate = maxOf("errorRate", since);
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("p95Peak", p95);
        metrics.put("dbRtMsPeak", dbRt);
        metrics.put("cacheHitRateMin", cacheHit);
        metrics.put("threadActivePeak", threadActive);
        metrics.put("threadMax", threadMax);
        metrics.put("downstreamRtMsPeak", downstreamRt);
        metrics.put("errorRatePeak", errorRate);

        // 日志 pattern 聚合
        List<LogEntry> logs = logEntryMapper.selectList(new QueryWrapper<LogEntry>()
                .eq("service", serviceName).ge("ts", since).orderByDesc("ts").last("LIMIT 300"));
        Map<String, Integer> patternCount = new LinkedHashMap<>();
        for (LogEntry l : logs) {
            patternCount.merge(l.getPattern() == null ? "UNKNOWN" : l.getPattern(), 1, Integer::sum);
        }
        List<String> logPatterns = patternCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(4)
                .map(e -> e.getKey() + "×" + e.getValue())
                .toList();

        Set<String> triggers = new LinkedHashSet<>();
        List<String> causes = new ArrayList<>();
        int slowLogs = patternCount.getOrDefault("SLOW_QUERY", 0);
        int cacheMissLogs = patternCount.getOrDefault("CACHE_MISS", 0);
        int cacheLowLogs = patternCount.getOrDefault("CACHE_HIT_LOW", 0);
        int downstreamErrLogs = patternCount.getOrDefault("DOWNSTREAM_ERROR", 0);
        int threadLogs = patternCount.getOrDefault("THREAD_POOL", 0);

        boolean cacheAbnormal = (cacheHit > 0 && cacheHit < 70) || cacheMissLogs >= 5 || cacheLowLogs >= 1;
        boolean slowSql = dbRt > 500 || p95 > 800 || slowLogs >= 3;
        boolean threadSaturated = (threadMax > 0 && threadActive / threadMax > 0.8) || threadLogs >= 1;
        boolean downstreamSlow = downstreamRt > 1000 || downstreamErrLogs >= 2;
        boolean errorHigh = errorRate > 5 || downstreamErrLogs >= 2;

        if (cacheAbnormal) {
            triggers.add("CACHE_HIT_LOW");
            causes.add("缓存命中率下降，回源 DB 放大（缓存击穿/雪崩）");
        }
        if (slowSql) {
            triggers.add("SLOW_SQL");
            causes.add("慢 SQL 导致数据库响应变慢、接口延迟升高");
        }
        if (threadSaturated) {
            triggers.add("THREAD_POOL_SATURATED");
            causes.add("订单线程池队列积压，请求排队甚至被拒绝");
        }
        if (downstreamSlow) {
            triggers.add("DOWNSTREAM_SLOW");
            causes.add("下游库存服务超时拖垮调用方");
        } else if (errorHigh) {
            triggers.add("ERROR_RATE_HIGH");
            causes.add("下游异常导致接口错误率升高");
        }
        if (cacheMissLogs > 0) {
            triggers.add("CACHE_MISS_LOG");
        }
        if (slowLogs > 0) {
            triggers.add("SLOW_QUERY_LOG");
        }
        if (downstreamErrLogs > 0) {
            triggers.add("DOWNSTREAM_ERROR_LOG");
        }
        if (threadLogs > 0) {
            triggers.add("THREAD_POOL_LOG");
        }

        String causeTitle = causes.isEmpty() ? "未发现显著异常，建议持续观察" : String.join("；", causes);
        // 处置建议按最严重症状优先：线程池打满 > 缓存异常 > 下游异常 > 慢 SQL
        String suggestedAction;
        if (threadSaturated) {
            suggestedAction = "SCALE_THREAD_POOL";
        } else if (cacheAbnormal) {
            suggestedAction = "CLEAR_CACHE";
        } else if (downstreamSlow || errorHigh) {
            suggestedAction = "ENABLE_RATE_LIMIT";
        } else if (slowSql) {
            suggestedAction = "RECOVER_FAULT";
        } else {
            suggestedAction = null;
        }

        String summary = "p95峰值=%.0fms DB-RT峰值=%.0fms 缓存命中最低=%.1f%% 线程峰值=%d/%d 下游峰值=%.0fms 错误率峰值=%.1f%%；日志TOP：%s"
                .formatted(p95, dbRt, cacheHit, (int) threadActive, (int) threadMax, downstreamRt, errorRate,
                        String.join("、", logPatterns));

        List<String> sources = new ArrayList<>();
        sources.add("metric: p95/dbRtMs/cacheHitRate/threadActive/downstreamRtMs/errorRate");
        if (!logPatterns.isEmpty()) {
            sources.add("log: " + String.join("、", logPatterns));
        }

        return new Snapshot(metrics, new ArrayList<>(triggers), logPatterns, causeTitle, summary,
                suggestedAction, sources);
    }

    private double maxOf(String metric, LocalDateTime since) {
        double max = 0;
        for (MetricPoint p : points(metric, since)) {
            max = Math.max(max, p.getValue());
        }
        return max;
    }

    private double minPositiveOf(String metric, LocalDateTime since) {
        double min = Double.MAX_VALUE;
        for (MetricPoint p : points(metric, since)) {
            if (p.getValue() > 0) {
                min = Math.min(min, p.getValue());
            }
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    private List<MetricPoint> points(String metric, LocalDateTime since) {
        return metricPointMapper.selectList(new QueryWrapper<MetricPoint>()
                .eq("service", serviceName).eq("metric", metric)
                .ge("ts", since).orderByAsc("ts").last("LIMIT 2000"));
    }
}
