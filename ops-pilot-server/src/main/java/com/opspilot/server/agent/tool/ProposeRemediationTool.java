package com.opspilot.server.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.Tool;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.entity.RemediationAction;
import com.opspilot.server.mapper.RemediationActionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提议处置动作：只创建 PENDING 审批单，绝不直接执行。
 */
@Component
public class ProposeRemediationTool implements Tool {

    private static final List<String> ALLOWED = List.of(
            "RECOVER_FAULT", "ENABLE_RATE_LIMIT", "CLEAR_CACHE", "ROLLBACK_CONFIG", "SCALE_THREAD_POOL");

    private static final Map<String, String> DEFAULT_RISK = Map.of(
            "RECOVER_FAULT", "MEDIUM",
            "ENABLE_RATE_LIMIT", "MEDIUM",
            "CLEAR_CACHE", "LOW",
            "ROLLBACK_CONFIG", "HIGH",
            "SCALE_THREAD_POOL", "MEDIUM");

    private final RemediationActionMapper actionMapper;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${opspilot.service-name}")
    private String defaultService;

    public ProposeRemediationTool(RemediationActionMapper actionMapper, EvidenceService evidenceService) {
        this.actionMapper = actionMapper;
        this.evidenceService = evidenceService;
    }

    @Override
    public String name() {
        return "propose_remediation";
    }

    @Override
    public String description() {
        return "提议处置动作（RECOVER_FAULT/ENABLE_RATE_LIMIT/CLEAR_CACHE/ROLLBACK_CONFIG/SCALE_THREAD_POOL），仅创建 PENDING 审批单，需人工批准后才会执行";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "incidentId", Map.of("type", "integer", "description", "关联告警 ID"),
                        "actionType", Map.of("type", "string", "description", "动作类型：" + ALLOWED + "，必填"),
                        "target", Map.of("type", "string", "description", "作用目标，默认当前服务"),
                        "params", Map.of("type", "object", "description", "动作参数，如 {\"type\":\"SLOW_SQL\"}"),
                        "reason", Map.of("type", "string", "description", "提议理由，必填"),
                        "riskLevel", Map.of("type", "string", "description", "LOW/MEDIUM/HIGH，默认按动作类型推断")),
                "required", List.of("actionType", "reason"));
    }

    @Override
    public String riskLevel() {
        return "WRITE";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String actionType = ToolArgs.str(args, "actionType", null);
        if (actionType == null || !ALLOWED.contains(actionType)) {
            return ToolResult.fail("actionType 非法，允许值：" + ALLOWED);
        }
        String reason = ToolArgs.str(args, "reason", null);
        if (reason == null) {
            return ToolResult.fail("缺少必填参数 reason");
        }
        String target = ToolArgs.str(args, "target", defaultService);
        String risk = ToolArgs.str(args, "riskLevel", DEFAULT_RISK.get(actionType));
        Long incidentId = ToolArgs.longVal(args, "incidentId");
        if (incidentId == null) {
            incidentId = ctx.incidentId();
        }

        RemediationAction action = new RemediationAction();
        action.setIncidentId(incidentId);
        action.setRunId(ctx.runId());
        action.setActionType(actionType);
        action.setTarget(target);
        Object params = args.get("params");
        if (params != null) {
            try {
                action.setParams(objectMapper.writeValueAsString(params));
            } catch (Exception e) {
                action.setParams(String.valueOf(params));
            }
        }
        action.setRiskLevel(risk);
        action.setReason(reason);
        action.setStatus("PENDING");
        action.setCreatedBy("agent");
        action.setCreatedAt(LocalDateTime.now());
        actionMapper.insert(action);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionId", action.getId());
        data.put("actionType", actionType);
        data.put("target", target);
        data.put("riskLevel", risk);
        data.put("status", "PENDING");

        String summary = "已创建待审批动作 #%d：%s → %s（风险 %s），需人工批准后执行"
                .formatted(action.getId(), actionType, target, risk);
        String eid = evidenceService.create(ctx.runIdOrZero(), "ACTION", String.valueOf(action.getId()),
                "提议处置 " + actionType, summary, data);
        return ToolResult.ok(summary, data, eid);
    }
}
