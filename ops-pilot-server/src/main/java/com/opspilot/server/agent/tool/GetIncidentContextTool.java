package com.opspilot.server.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.Tool;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.client.DemoClient;
import com.opspilot.server.entity.Incident;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.mapper.IncidentMapper;
import com.opspilot.server.mapper.MetricPointMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 获取告警上下文：incident 详情 + 关键指标最新值 + demo 当前故障注入状态。
 */
@Component
public class GetIncidentContextTool implements Tool {

    private static final List<String> KEY_METRICS = List.of(
            "qps", "p95", "errorRate", "dbRtMs", "cacheHitRate",
            "threadActive", "threadQueue", "threadRejected", "downstreamRtMs");

    private final IncidentMapper incidentMapper;
    private final MetricPointMapper metricPointMapper;
    private final DemoClient demoClient;
    private final EvidenceService evidenceService;

    @Value("${opspilot.service-name}")
    private String defaultService;

    public GetIncidentContextTool(IncidentMapper incidentMapper, MetricPointMapper metricPointMapper,
                                  DemoClient demoClient, EvidenceService evidenceService) {
        this.incidentMapper = incidentMapper;
        this.metricPointMapper = metricPointMapper;
        this.demoClient = demoClient;
        this.evidenceService = evidenceService;
    }

    @Override
    public String name() {
        return "get_incident_context";
    }

    @Override
    public String description() {
        return "获取告警事件上下文：告警详情、关键指标最新值、当前故障注入状态";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "incidentId", Map.of("type", "integer", "description", "告警事件 ID，必填")),
                "required", List.of("incidentId"));
    }

    @Override
    public String riskLevel() {
        return "READ";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Long incidentId = ToolArgs.longVal(args, "incidentId");
        Map<String, Object> data = new LinkedHashMap<>();
        String service;
        String summaryPrefix;
        if (incidentId == null) {
            // 无关联告警：退化为服务级上下文，避免 Agent 在无 incidentId 时反复失败
            service = defaultService;
            data.put("incident", null);
            summaryPrefix = "无关联告警事件，返回服务级上下文 " + service;
        } else {
            Incident incident = incidentMapper.selectById(incidentId);
            if (incident == null) {
                return ToolResult.fail("incident #" + incidentId + " 不存在");
            }
            service = incident.getService();
            Map<String, Object> incidentMap = new LinkedHashMap<>();
            incidentMap.put("id", incident.getId());
            incidentMap.put("service", incident.getService());
            incidentMap.put("title", incident.getTitle());
            incidentMap.put("severity", incident.getSeverity());
            incidentMap.put("status", incident.getStatus());
            incidentMap.put("startedAt", incident.getStartedAt() == null ? null : incident.getStartedAt().toString());
            data.put("incident", incidentMap);
            summaryPrefix = "告警 #%d [%s] %s".formatted(
                    incident.getId(), incident.getSeverity(), incident.getTitle());
        }

        // 关键指标最新值
        Map<String, Object> latestMetrics = new LinkedHashMap<>();
        for (String metric : KEY_METRICS) {
            MetricPoint p = metricPointMapper.selectOne(new QueryWrapper<MetricPoint>()
                    .eq("service", service).eq("metric", metric)
                    .orderByDesc("ts").last("LIMIT 1"));
            if (p != null) {
                latestMetrics.put(metric, p.getValue());
            }
        }

        // demo 当前故障注入状态
        Object faultStatus;
        try {
            faultStatus = demoClient.getFaultStatus();
        } catch (Exception e) {
            faultStatus = Map.of("error", "demo 不可达: " + e.getMessage());
        }

        data.put("latestMetrics", latestMetrics);
        data.put("faultStatus", faultStatus);

        String summary = summaryPrefix + "；最新指标 " + latestMetrics;
        String target = incidentId == null ? service : String.valueOf(incidentId);
        String eid = evidenceService.create(ctx.runIdOrZero(), "INCIDENT", target,
                incidentId == null ? "服务上下文 " + service : "告警上下文 #" + incidentId, summary, data);
        return ToolResult.ok(summary, data, eid);
    }
}
