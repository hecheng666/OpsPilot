package com.opspilot.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.entity.*;
import com.opspilot.server.mapper.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事故中心：列表、详情（含 Agent 运行/证据/待审批动作）、关闭。
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentMapper incidentMapper;
    private final AgentRunMapper agentRunMapper;
    private final EvidenceMapper evidenceMapper;
    private final RemediationActionMapper actionMapper;

    public IncidentController(IncidentMapper incidentMapper, AgentRunMapper agentRunMapper,
                              EvidenceMapper evidenceMapper, RemediationActionMapper actionMapper) {
        this.incidentMapper = incidentMapper;
        this.agentRunMapper = agentRunMapper;
        this.evidenceMapper = evidenceMapper;
        this.actionMapper = actionMapper;
    }

    @GetMapping
    public List<Incident> list(@RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "50") int limit) {
        QueryWrapper<Incident> q = new QueryWrapper<Incident>()
                .orderByDesc("created_at").last("LIMIT " + Math.min(Math.max(limit, 1), 200));
        if (status != null && !status.isBlank()) {
            q.eq("status", status);
        }
        return incidentMapper.selectList(q);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Incident incident = incidentMapper.selectById(id);
        if (incident == null) {
            resp.put("error", "事故不存在");
            return resp;
        }
        resp.put("incident", incident);
        List<AgentRun> runs = agentRunMapper.selectList(new QueryWrapper<AgentRun>()
                .eq("incident_id", id).orderByDesc("id"));
        resp.put("runs", runs);
        if (!runs.isEmpty()) {
            Long latestRunId = runs.get(0).getId();
            resp.put("evidences", evidenceMapper.selectList(new QueryWrapper<Evidence>()
                    .eq("run_id", latestRunId).orderByAsc("id")));
        } else {
            resp.put("evidences", List.of());
        }
        resp.put("actions", actionMapper.selectList(new QueryWrapper<RemediationAction>()
                .eq("incident_id", id).orderByDesc("id")));
        return resp;
    }

    @PostMapping("/{id}/close")
    public Map<String, Object> close(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Incident incident = incidentMapper.selectById(id);
        if (incident == null) {
            resp.put("success", false);
            resp.put("summary", "事故不存在");
            return resp;
        }
        incident.setStatus("CLOSED");
        incident.setResolvedAt(LocalDateTime.now());
        incidentMapper.updateById(incident);
        resp.put("success", true);
        resp.put("summary", "事故 #" + id + " 已关闭");
        return resp;
    }
}
