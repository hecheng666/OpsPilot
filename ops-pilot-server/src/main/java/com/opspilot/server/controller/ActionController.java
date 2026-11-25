package com.opspilot.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.agent.tool.ExecuteRemediationTool;
import com.opspilot.server.entity.AuditLog;
import com.opspilot.server.entity.RemediationAction;
import com.opspilot.server.mapper.AuditLogMapper;
import com.opspilot.server.mapper.RemediationActionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 处置动作审批：人工批准/拒绝。批准后立即执行（执行工具内部仍校验 APPROVED 状态）。
 */
@RestController
@RequestMapping("/api/actions")
public class ActionController {

    private final RemediationActionMapper actionMapper;
    private final AuditLogMapper auditLogMapper;
    private final ExecuteRemediationTool executeTool;

    @Value("${opspilot.service-name}")
    private String serviceName;

    public ActionController(RemediationActionMapper actionMapper, AuditLogMapper auditLogMapper,
                            ExecuteRemediationTool executeTool) {
        this.actionMapper = actionMapper;
        this.auditLogMapper = auditLogMapper;
        this.executeTool = executeTool;
    }

    @GetMapping
    public List<RemediationAction> list(@RequestParam(required = false) String status) {
        QueryWrapper<RemediationAction> q = new QueryWrapper<RemediationAction>()
                .orderByDesc("id").last("LIMIT 100");
        if (status != null && !status.isBlank()) {
            q.eq("status", status);
        }
        return actionMapper.selectList(q);
    }

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        RemediationAction action = actionMapper.selectById(id);
        if (action == null) {
            resp.put("success", false);
            resp.put("summary", "动作 #" + id + " 不存在");
            return resp;
        }
        if (!"PENDING".equals(action.getStatus())) {
            resp.put("success", false);
            resp.put("summary", "动作 #%d 当前状态 %s，仅 PENDING 可审批".formatted(id, action.getStatus()));
            return resp;
        }
        String actor = body != null && body.get("actor") != null
                ? String.valueOf(body.get("actor")) : "admin";
        action.setStatus("APPROVED");
        action.setApprovedBy(actor);
        action.setApprovedAt(LocalDateTime.now());
        actionMapper.updateById(action);
        audit(actor, "APPROVE_REMEDIATION", "action#" + id,
                "批准 %s → %s".formatted(action.getActionType(), action.getTarget()));

        // 批准后立刻执行；执行工具内部仍要求 APPROVED 状态
        ToolContext ctx = ToolContext.of(action.getRunId(), action.getIncidentId(), serviceName);
        ToolResult r = executeTool.execute(Map.of("actionId", id), ctx);
        resp.put("success", true);
        resp.put("approvedBy", actor);
        resp.put("execute", Map.of(
                "success", r.success(),
                "summary", r.summary(),
                "evidenceId", r.evidenceId() == null ? "" : r.evidenceId()));
        return resp;
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        RemediationAction action = actionMapper.selectById(id);
        if (action == null) {
            resp.put("success", false);
            resp.put("summary", "动作 #" + id + " 不存在");
            return resp;
        }
        if (!"PENDING".equals(action.getStatus())) {
            resp.put("success", false);
            resp.put("summary", "动作 #%d 当前状态 %s，仅 PENDING 可拒绝".formatted(id, action.getStatus()));
            return resp;
        }
        String actor = body != null && body.get("actor") != null
                ? String.valueOf(body.get("actor")) : "admin";
        String reason = body != null && body.get("reason") != null
                ? String.valueOf(body.get("reason")) : "";
        action.setStatus("REJECTED");
        action.setApprovedBy(actor);
        action.setApprovedAt(LocalDateTime.now());
        actionMapper.updateById(action);
        audit(actor, "REJECT_REMEDIATION", "action#" + id,
                "拒绝 %s，原因：%s".formatted(action.getActionType(), reason));

        resp.put("success", true);
        resp.put("summary", "动作 #" + id + " 已拒绝");
        return resp;
    }

    private void audit(String actor, String op, String target, String detail) {
        AuditLog log = new AuditLog();
        log.setActor(actor);
        log.setAction(op);
        log.setTarget(target);
        log.setDetail(detail);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
