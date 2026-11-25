package com.opspilot.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.entity.EvalResult;
import com.opspilot.server.entity.EvalRun;
import com.opspilot.server.entity.EvalScenario;
import com.opspilot.server.eval.EvalService;
import com.opspilot.server.mapper.EvalResultMapper;
import com.opspilot.server.mapper.EvalRunMapper;
import com.opspilot.server.mapper.EvalScenarioMapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测中心：场景列表、启动抽样/完整评测、结果查询与导出。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;
    private final EvalScenarioMapper scenarioMapper;
    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;

    public EvalController(EvalService evalService, EvalScenarioMapper scenarioMapper,
                          EvalRunMapper runMapper, EvalResultMapper resultMapper) {
        this.evalService = evalService;
        this.scenarioMapper = scenarioMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
    }

    @GetMapping("/scenarios")
    public List<EvalScenario> scenarios() {
        return scenarioMapper.selectList(new QueryWrapper<EvalScenario>().orderByAsc("id"));
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String baseline = body.get("baseline") == null ? "FULL_AGENT" : String.valueOf(body.get("baseline"));
        int waitSeconds = body.get("waitSeconds") instanceof Number n ? n.intValue() : 12;
        List<Long> scenarioIds = new ArrayList<>();
        if (body.get("scenarioIds") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number num) {
                    scenarioIds.add(num.longValue());
                }
            }
        }
        try {
            EvalRun run = evalService.start(baseline, scenarioIds, waitSeconds);
            resp.put("success", true);
            resp.put("evalRunId", run.getId());
            resp.put("baseline", run.getBaseline());
            resp.put("total", run.getTotal());
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("summary", e.getMessage());
        }
        return resp;
    }

    @GetMapping("/runs")
    public List<EvalRun> runs(@RequestParam(defaultValue = "20") int limit) {
        return runMapper.selectList(new QueryWrapper<EvalRun>()
                .orderByDesc("id").last("LIMIT " + Math.min(Math.max(limit, 1), 100)));
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        EvalRun run = runMapper.selectById(id);
        if (run == null) {
            resp.put("error", "评测运行不存在");
            return resp;
        }
        resp.put("run", run);
        resp.put("progress", evalService.progressOf(id));
        if (run.getStartedAt() != null) {
            resp.put("unauthorizedWrites", evalService.unauthorizedWrites(run.getStartedAt()));
        }

        List<EvalResult> results = resultMapper.selectList(new QueryWrapper<EvalResult>()
                .eq("eval_run_id", id).orderByAsc("id"));
        Map<Long, EvalScenario> scenarios = new HashMap<>();
        if (!results.isEmpty()) {
            List<Long> ids = results.stream().map(EvalResult::getScenarioId).distinct().toList();
            scenarioMapper.selectBatchIds(ids).forEach(s -> scenarios.put(s.getId(), s));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EvalResult r : results) {
            EvalScenario s = scenarios.get(r.getScenarioId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resultId", r.getId());
            m.put("scenarioId", r.getScenarioId());
            m.put("scenarioName", s == null ? "" : s.getName());
            m.put("faultType", s == null ? "" : s.getFaultType());
            m.put("expectedRootCause", s == null ? "" : s.getExpectedRootCause());
            m.put("expectedAction", s == null ? "" : s.getExpectedAction());
            m.put("detectedRootCause", r.getDetectedRootCause());
            m.put("hitRank", r.getHitRank());
            m.put("steps", r.getSteps());
            m.put("latencyMs", r.getLatencyMs());
            m.put("evidenceOk", r.getEvidenceOk() != null && r.getEvidenceOk() == 1);
            m.put("reportScore", r.getReportScore());
            m.put("report", r.getReport());
            rows.add(m);
        }
        resp.put("results", rows);
        return resp;
    }

    /** 导出评测报告 JSON（前端也可直接基于 runs/{id} 导出） */
    @GetMapping("/runs/{id}/export")
    public Map<String, Object> export(@PathVariable Long id) {
        Map<String, Object> detail = runDetail(id);
        detail.put("exportedAt", java.time.LocalDateTime.now().toString());
        detail.put("project", "OpsPilot");
        return detail;
    }
}
