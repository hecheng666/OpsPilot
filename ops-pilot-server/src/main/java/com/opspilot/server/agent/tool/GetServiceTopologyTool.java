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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务依赖拓扑：order-demo 的静态依赖（MySQL / Caffeine 缓存 / 下游库存服务），
 * 并附各依赖最新指标做出的健康判断。
 */
@Component
public class GetServiceTopologyTool implements Tool {

    private final MetricPointMapper metricPointMapper;
    private final EvidenceService evidenceService;

    @Value("${opspilot.service-name}")
    private String defaultService;

    public GetServiceTopologyTool(MetricPointMapper metricPointMapper, EvidenceService evidenceService) {
        this.metricPointMapper = metricPointMapper;
        this.evidenceService = evidenceService;
    }

    @Override
    public String name() {
        return "get_service_topology";
    }

    @Override
    public String description() {
        return "获取服务依赖拓扑（数据库/缓存/下游服务）及各依赖基于最新指标的健康状态";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "服务名，默认当前被观测服务")),
                "required", List.of());
    }

    @Override
    public String riskLevel() {
        return "READ";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String service = ToolArgs.str(args, "service", defaultService);

        List<Map<String, Object>> deps = new ArrayList<>();
        // MySQL 订单库：看 dbRtMs
        Double dbRt = latestMetric(service, "dbRtMs");
        deps.add(dep("mysql", "DATABASE", "ops_demo 订单库",
                dbRt == null ? "UNKNOWN" : dbRt > 500 ? "UNHEALTHY" : "HEALTHY",
                "dbRtMs", dbRt, "dbRtMs>500ms 判定异常"));
        // Caffeine 缓存：看 cacheHitRate（百分比）
        Double hitRate = latestMetric(service, "cacheHitRate");
        deps.add(dep("caffeine", "CACHE", "本地缓存",
                hitRate == null ? "UNKNOWN" : hitRate < 60 ? "UNHEALTHY" : "HEALTHY",
                "cacheHitRate", hitRate, "cacheHitRate<60% 判定异常"));
        // 下游库存服务：看 downstreamRtMs
        Double downRt = latestMetric(service, "downstreamRtMs");
        deps.add(dep("inventory-svc", "DOWNSTREAM", "库存下游服务",
                downRt == null ? "UNKNOWN" : downRt > 1000 ? "UNHEALTHY" : "HEALTHY",
                "downstreamRtMs", downRt, "downstreamRtMs>1000ms 判定异常"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("dependencies", deps);

        long unhealthy = deps.stream().filter(d -> "UNHEALTHY".equals(d.get("health"))).count();
        String summary = "依赖拓扑：%d 个依赖，%d 个异常".formatted(deps.size(), unhealthy);
        String eid = evidenceService.create(ctx.runIdOrZero(), "TOPOLOGY", service,
                "服务依赖拓扑", summary, data);
        return ToolResult.ok(summary, data, eid);
    }

    private Double latestMetric(String service, String metric) {
        MetricPoint p = metricPointMapper.selectOne(new QueryWrapper<MetricPoint>()
                .eq("service", service).eq("metric", metric)
                .orderByDesc("ts").last("LIMIT 1"));
        return p == null ? null : p.getValue();
    }

    private static Map<String, Object> dep(String name, String type, String desc, String health,
                                           String metric, Double value, String rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("description", desc);
        m.put("health", health);
        m.put("metric", metric);
        m.put("latestValue", value);
        m.put("healthRule", rule);
        return m;
    }
}
