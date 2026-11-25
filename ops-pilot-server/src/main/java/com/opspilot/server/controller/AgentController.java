package com.opspilot.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.agent.AgentService;
import com.opspilot.server.entity.AgentRun;
import com.opspilot.server.entity.AgentStep;
import com.opspilot.server.entity.Evidence;
import com.opspilot.server.mapper.AgentRunMapper;
import com.opspilot.server.mapper.EvidenceMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 排查入口：SSE 流式（前端实时看步骤）+ 同步接口（联调/评测用）+ 运行回放。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final AgentRunMapper runMapper;
    private final EvidenceMapper evidenceMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AgentController(AgentService agentService, AgentRunMapper runMapper,
                           EvidenceMapper evidenceMapper) {
        this.agentService = agentService;
        this.runMapper = runMapper;
        this.evidenceMapper = evidenceMapper;
    }

    /** SSE 流式排查：前端实时接收 step/report/done 事件 */
    @GetMapping("/investigate/stream")
    public SseEmitter stream(@RequestParam(required = false) Long incidentId,
                             @RequestParam(required = false) String input) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
        Long runId = agentService.createRun(incidentId, input);
        executor.submit(() -> {
            try {
                agentService.investigate(runId, emitter);
            } finally {
                emitter.complete();
            }
        });
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitter.complete());
        return emitter;
    }

    /** 同步排查：等执行完返回完整报告（curl/评测用） */
    @PostMapping("/investigate")
    public Map<String, Object> investigate(@RequestBody(required = false) Map<String, Object> body) {
        Long incidentId = body != null && body.get("incidentId") instanceof Number n
                ? n.longValue() : null;
        String input = body == null ? null : (String) body.get("input");
        Long runId = agentService.createRun(incidentId, input);
        AgentRun run = agentService.investigate(runId, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", runId);
        resp.put("status", run.getStatus());
        resp.put("degraded", run.getDegraded() != null && run.getDegraded() == 1);
        resp.put("latencyMs", run.getLatencyMs());
        resp.put("promptTokens", run.getPromptTokens());
        resp.put("completionTokens", run.getCompletionTokens());
        try {
            resp.put("report", run.getReport() == null ? null : objectMapper.readValue(run.getReport(), Map.class));
        } catch (Exception e) {
            resp.put("report", run.getReport());
        }
        return resp;
    }

    /** 运行历史列表 */
    @GetMapping("/runs")
    public List<AgentRun> runs(@RequestParam(required = false) Long incidentId) {
        QueryWrapper<AgentRun> q = new QueryWrapper<AgentRun>()
                .orderByDesc("id").last("LIMIT 50");
        if (incidentId != null) {
            q.eq("incident_id", incidentId);
        }
        return runMapper.selectList(q);
    }

    /** 单次运行回放：run + steps + 证据 */
    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        AgentRun run = runMapper.selectById(id);
        resp.put("run", run);
        List<AgentStep> steps = agentService.stepsOf(id);
        resp.put("steps", steps);
        List<Evidence> evidences = evidenceMapper.selectList(new QueryWrapper<Evidence>()
                .eq("run_id", id).orderByAsc("id"));
        resp.put("evidences", evidences);
        if (run != null && run.getReport() != null) {
            try {
                resp.put("report", objectMapper.readValue(run.getReport(), Map.class));
            } catch (Exception e) {
                resp.put("report", run.getReport());
            }
        }
        return resp;
    }
}
