package com.opspilot.server.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.Tool;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.mapper.MetricPointMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询服务指标时序，返回均值/峰值/最新值/异常分与最近序列。
 */
@Component
public class QueryMetricsTool implements Tool {

    private final MetricPointMapper metricPointMapper;
    private final EvidenceService evidenceService;

    @Value("${opspilot.service-name}")
    private String defaultService;

    public QueryMetricsTool(MetricPointMapper metricPointMapper, EvidenceService evidenceService) {
        this.metricPointMapper = metricPointMapper;
        this.evidenceService = evidenceService;
    }

    @Override
    public String name() {
        return "query_metrics";
    }

    @Override
    public String description() {
        return "查询服务指标时序（qps/p95/p99/errorRate/dbRtMs/cacheHitRate/threadActive/threadQueue/threadRejected/downstreamRtMs），返回统计与异常分";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "服务名，默认当前被观测服务"),
                        "metric", Map.of("type", "string", "description", "指标名，必填"),
                        "windowMinutes", Map.of("type", "integer", "description", "时间窗口分钟数，1-60，默认 30")),
                "required", List.of("metric"));
    }

    @Override
    public String riskLevel() {
        return "READ";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String metric = ToolArgs.str(args, "metric", null);
        if (metric == null) {
            return ToolResult.fail("缺少必填参数 metric");
        }
        String service = ToolArgs.str(args, "service", defaultService);
        int window = ToolArgs.intVal(args, "windowMinutes", 30, 60);
        LocalDateTime since = ctx.since() != null ? ctx.since() : LocalDateTime.now().minusMinutes(window);

        List<MetricPoint> points = metricPointMapper.selectList(new QueryWrapper<MetricPoint>()
                .eq("service", service).eq("metric", metric)
                .ge("ts", since).orderByAsc("ts").last("LIMIT 2000"));
        if (points.isEmpty()) {
            return ToolResult.fail("指标 " + metric + " 最近 " + window + " 分钟无数据");
        }

        double sum = 0, max = Double.MIN_VALUE, min = Double.MAX_VALUE;
        for (MetricPoint p : points) {
            sum += p.getValue();
            max = Math.max(max, p.getValue());
            min = Math.min(min, p.getValue());
        }
        double avg = sum / points.size();
        double latest = points.get(points.size() - 1).getValue();
        double var = 0;
        for (MetricPoint p : points) {
            var += (p.getValue() - avg) * (p.getValue() - avg);
        }
        double std = Math.sqrt(var / points.size());
        // 异常分：最新值偏离均值的标准差倍数，|z|/3 截断到 [0,1]
        double anomaly = std < 1e-9 ? 0 : Math.min(1.0, Math.abs(latest - avg) / (3 * std));

        List<Map<String, Object>> series = new ArrayList<>();
        int from = Math.max(0, points.size() - 20);
        for (int i = from; i < points.size(); i++) {
            MetricPoint p = points.get(i);
            series.add(Map.of("ts", p.getTs().toString(), "value", p.getValue()));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("metric", metric);
        data.put("windowMinutes", window);
        data.put("count", points.size());
        data.put("avg", round(avg));
        data.put("max", round(max));
        data.put("min", round(min));
        data.put("latest", round(latest));
        data.put("anomalyScore", round(anomaly));
        data.put("series", series);

        String summary = "%s 最近%d分钟：均值%.2f 峰值%.2f 最新%.2f 异常分%.2f（%d 个点）"
                .formatted(metric, window, avg, max, latest, anomaly, points.size());
        String eid = evidenceService.create(ctx.runIdOrZero(), "METRIC", service + "/" + metric,
                "指标 " + metric + " 最近" + window + "分钟统计", summary, data);
        return ToolResult.ok(summary, data, eid);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
