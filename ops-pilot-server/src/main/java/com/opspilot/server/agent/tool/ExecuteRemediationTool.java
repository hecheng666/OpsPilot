package com.opspilot.server.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.Tool;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.client.DemoClient;
import com.opspilot.server.entity.AuditLog;
import com.opspilot.server.entity.RemediationAction;
import com.opspilot.server.mapper.AuditLogMapper;
import com.opspilot.server.mapper.RemediationActionMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行处置动作：仅 APPROVED 状态可执行（Agent 永不自批），执行结果写审计日志。
 */
@Component
public class ExecuteRemediationTool implements Tool {

    private final RemediationActionMapper actionMapper;
    private final AuditLogMapper auditLogMapper;
    private final DemoClient demoClient;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExecuteRemediationTool(RemediationActionMapper actionMapper, AuditLogMapper auditLogMapper,
                                  DemoClient demoClient, EvidenceService evidenceService) {
        this.actionMapper = actionMapper;
        this.auditLogMapper = auditLogMapper;
        this.demoClient = demoClient;
        this.evidenceService = evidenceService;
    }

    @Override
    public String name() {
        return "execute_remediation";
    }

    @Override
    public String description() {
        return "执行已批准的处置动作（仅 APPROVED 状态可执行），调用 demo 内部接口生效并写审计日志";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "actionId", Map.of("type", "integer", "description", "处置动作 ID，必填")),
                "required", List.of("actionId"));
    }

    @Override
    public String riskLevel() {
        return "CRITICAL";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        Long actionId = ToolArgs.longVal(args, "actionId");
        if (actionId == null) {
            return ToolResult.fail("缺少必填参数 actionId");
        }
        RemediationAction action = actionMapper.selectById(actionId);
        if (action == null) {
            return ToolResult.fail("动作 #" + actionId + " 不存在");
        }
        // 权限闸门：未批准一律拒绝，Agent 无权自批
        if (!"APPROVED".equals(action.getStatus())) {
            return ToolResult.fail("动作 #%d 当前状态 %s，仅 APPROVED 可执行（需人工审批）"
                    .formatted(actionId, action.getStatus()));
        }

        Map<String, Object> params = Map.of();
        if (action.getParams() != null && !action.getParams().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(action.getParams(), Map.class);
                params = parsed;
            } catch (Exception ignored) {
                // 解析失败按空参数执行
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionId", actionId);
        data.put("actionType", action.getActionType());
        try {
            Map<String, Object> demoResult = demoClient.executeAction(Map.of(
                    "actionType", action.getActionType(),
                    "params", params));
            action.setStatus("EXECUTED");
            action.setResult(truncate(String.valueOf(demoResult)));
            action.setExecutedAt(LocalDateTime.now());
            actionMapper.updateById(action);
            audit(action, "EXECUTE_REMEDIATION", "执行成功：" + truncate(String.valueOf(demoResult)));

            data.put("status", "EXECUTED");
            data.put("demoResult", demoResult);
            String summary = "动作 #%d %s 执行成功".formatted(actionId, action.getActionType());
            String eid = evidenceService.create(ctx.runIdOrZero(), "ACTION", String.valueOf(actionId),
                    "执行处置 " + action.getActionType(), summary, data);
            return ToolResult.ok(summary, data, eid);
        } catch (Exception e) {
            action.setStatus("FAILED");
            action.setResult(truncate("调用 demo 失败: " + e.getMessage()));
            action.setExecutedAt(LocalDateTime.now());
            actionMapper.updateById(action);
            audit(action, "EXECUTE_REMEDIATION", "执行失败：" + e.getMessage());
            return ToolResult.fail("动作 #%d 执行失败：%s".formatted(actionId, e.getMessage()));
        }
    }

    private void audit(RemediationAction action, String op, String detail) {
        AuditLog log = new AuditLog();
        log.setActor("agent");
        log.setAction(op);
        log.setTarget("action#" + action.getId());
        log.setDetail(action.getActionType() + " " + detail);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }

    private static String truncate(String s) {
        return s != null && s.length() > 500 ? s.substring(0, 500) : s;
    }
}
