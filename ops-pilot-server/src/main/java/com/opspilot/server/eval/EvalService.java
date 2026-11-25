package com.opspilot.server.eval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.agent.AgentService;
import com.opspilot.server.client.DemoClient;
import com.opspilot.server.entity.AgentRun;
import com.opspilot.server.entity.AuditLog;
import com.opspilot.server.entity.EvalResult;
import com.opspilot.server.entity.EvalRun;
import com.opspilot.server.entity.EvalScenario;
import com.opspilot.server.entity.Evidence;
import com.opspilot.server.mapper.AuditLogMapper;
import com.opspilot.server.mapper.EvalResultMapper;
import com.opspilot.server.mapper.EvalRunMapper;
import com.opspilot.server.mapper.EvalScenarioMapper;
import com.opspilot.server.mapper.EvidenceMapper;
import com.opspilot.server.rag.OllamaClient;
import com.opspilot.server.rag.RunbookSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 评测服务：场景循环 = 清理故障 → 注入 → 等待 2 个采集窗口 → 跑基线 → 评分 → 恢复。
 * 三基线：RULE（阈值规则）/ RAG_ONLY（Runbook 检索 + 单轮 LLM）/ FULL_AGENT（ReAct 全工具）。
 */
@Service
public class EvalService {

    private static final Logger log = LoggerFactory.getLogger(EvalService.class);

    private final EvalScenarioMapper scenarioMapper;
    private final EvalRunMapper runMapper;
    private final EvalResultMapper resultMapper;
    private final EvidenceMapper evidenceMapper;
    private final AuditLogMapper auditLogMapper;
    private final DemoClient demoClient;
    private final SymptomAnalyzer symptomAnalyzer;
    private final RunbookSearchService runbookSearchService;
    private final OllamaClient ollamaClient;
    private final AgentService agentService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<Long, Map<String, Object>> progressMap = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${opspilot.service-name}")
    private String serviceName;

    public EvalService(EvalScenarioMapper scenarioMapper, EvalRunMapper runMapper,
                       EvalResultMapper resultMapper, EvidenceMapper evidenceMapper,
                       AuditLogMapper auditLogMapper, DemoClient demoClient,
                       SymptomAnalyzer symptomAnalyzer, RunbookSearchService runbookSearchService,
                       OllamaClient ollamaClient, AgentService agentService) {
        this.scenarioMapper = scenarioMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.evidenceMapper = evidenceMapper;
        this.auditLogMapper = auditLogMapper;
        this.demoClient = demoClient;
        this.symptomAnalyzer = symptomAnalyzer;
        this.runbookSearchService = runbookSearchService;
        this.ollamaClient = ollamaClient;
        this.agentService = agentService;
    }

    /** 启动一次评测，立即返回 evalRunId；后台串行执行 */
    public EvalRun start(String baseline, List<Long> scenarioIds, int waitSeconds) {
        if (!List.of("RULE", "RAG_ONLY", "FULL_AGENT").contains(baseline)) {
            throw new IllegalArgumentException("baseline 必须是 RULE / RAG_ONLY / FULL_AGENT");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有评测任务在运行，请等待完成");
        }
        List<EvalScenario> scenarios;
        if (scenarioIds == null || scenarioIds.isEmpty()) {
            scenarios = scenarioMapper.selectList(new QueryWrapper<EvalScenario>()
                    .eq("enabled", 1).orderByAsc("id"));
        } else {
            scenarios = scenarioMapper.selectList(new QueryWrapper<EvalScenario>()
                    .in("id", scenarioIds).orderByAsc("id"));
        }
        if (scenarios.isEmpty()) {
            running.set(false);
            throw new IllegalStateException("没有可执行的场景");
        }

        EvalRun run = new EvalRun();
        run.setBaseline(baseline);
        run.setStatus("RUNNING");
        run.setTotal(scenarios.size());
        run.setHitTop1(0);
        run.setHitTop3(0);
        run.setAvgSteps(0.0);
        run.setAvgLatencyMs(0.0);
        run.setEvidenceAccuracy(0.0);
        run.setReportCompleteness(0.0);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);

        int wait = Math.min(Math.max(waitSeconds, 8), 30);
        executor.submit(() -> execute(run, scenarios, wait));
        return run;
    }

    private void execute(EvalRun run, List<EvalScenario> scenarios, int waitSeconds) {
        int done = 0, hit1 = 0, hit3 = 0;
        double sumSteps = 0, sumLatency = 0, sumEvidence = 0, sumReport = 0;
        try {
            for (EvalScenario s : scenarios) {
                progress(run.getId(), "RUNNING", done, scenarios.size(), s.getName(), "清理环境");
                recoverAll();
                sleep(1200);

                Map<String, Object> cfg = parseMap(s.getConfig());
                int intensity = cfg.get("intensity") instanceof Number n ? n.intValue() : 6;
                int duration = cfg.get("durationSeconds") instanceof Number n2 ? n2.intValue() : 120;
                LocalDateTime injectAt = LocalDateTime.now();
                inject(s.getFaultType(), intensity, Math.max(60, duration));

                progress(run.getId(), "RUNNING", done, scenarios.size(), s.getName(),
                        "故障已注入，等待指标劣化 " + waitSeconds + "s");
                sleep(waitSeconds * 1000L);

                progress(run.getId(), "RUNNING", done, scenarios.size(), s.getName(), "执行基线 " + run.getBaseline());
                Outcome o = switch (run.getBaseline()) {
                    case "RULE" -> runRule(injectAt);
                    case "RAG_ONLY" -> runRagOnly(injectAt);
                    default -> runFullAgent(s, injectAt);
                };

                List<String> keywords = parseKeywords(s.getExpectedRootCause());
                int rank = rank(keywords, o.rootCause(), o.alternatives());
                EvalResult r = new EvalResult();
                r.setEvalRunId(run.getId());
                r.setScenarioId(s.getId());
                r.setDetectedRootCause(truncate(o.rootCause(), 250));
                r.setHitRank(rank);
                r.setSteps(o.steps());
                r.setLatencyMs(o.latencyMs());
                r.setEvidenceOk(o.evidenceOk() ? 1 : 0);
                r.setReportScore(o.reportScore());
                r.setReport(o.reportJson());
                resultMapper.insert(r);

                recoverAll();
                done++;
                if (rank == 1) {
                    hit1++;
                }
                if (rank >= 1 && rank <= 3) {
                    hit3++;
                }
                sumSteps += o.steps();
                sumLatency += o.latencyMs();
                sumEvidence += o.evidenceOk() ? 1 : 0;
                sumReport += o.reportScore();
                updateAggregates(run, done, hit1, hit3, sumSteps, sumLatency, sumEvidence, sumReport);
            }
            run.setStatus("DONE");
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
            progress(run.getId(), "DONE", done, scenarios.size(), "", "评测完成");
        } catch (Exception e) {
            log.error("eval run #{} 执行失败", run.getId(), e);
            run.setStatus("FAILED");
            run.setFinishedAt(LocalDateTime.now());
            runMapper.updateById(run);
            progress(run.getId(), "FAILED", done, scenarios.size(), "", String.valueOf(e.getMessage()));
        } finally {
            recoverAll();
            running.set(false);
        }
    }

    private void updateAggregates(EvalRun run, int done, int hit1, int hit3,
                                  double sumSteps, double sumLatency, double sumEvidence, double sumReport) {
        run.setTotal(done);
        run.setHitTop1(hit1);
        run.setHitTop3(hit3);
        run.setAvgSteps(round(sumSteps / done));
        run.setAvgLatencyMs(round(sumLatency / done));
        run.setEvidenceAccuracy(round(sumEvidence / done));
        run.setReportCompleteness(round(sumReport / done));
        runMapper.updateById(run);
    }

    // ---------------- 三组基线 ----------------

    /** RULE：只看指标阈值 + 日志 pattern，不检索 runbook、不调用模型 */
    private Outcome runRule(LocalDateTime injectAt) {
        long t0 = System.currentTimeMillis();
        SymptomAnalyzer.Snapshot snap = symptomAnalyzer.analyze(injectAt);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", snap.summary());
        report.put("root_cause", Map.of("title", snap.causeTitle(),
                "confidence", snap.triggers().isEmpty() ? 0.3 : 0.6,
                "evidence_ids", List.of()));
        report.put("alternatives", List.of());
        report.put("impact", "规则触发：" + String.join("、", snap.triggers()));
        report.put("prevention", List.of("补充阈值告警与日志监控"));
        report.put("actions", List.of());
        return new Outcome(snap.causeTitle(), List.of(), 3,
                (int) (System.currentTimeMillis() - t0), !snap.sources().isEmpty(),
                completeness(report), json(report));
    }

    /** RAG_ONLY：Runbook 混合检索（+ 可选单轮 LLM 综合），不做多步工具排查 */
    private Outcome runRagOnly(LocalDateTime injectAt) {
        long t0 = System.currentTimeMillis();
        SymptomAnalyzer.Snapshot snap = symptomAnalyzer.analyze(injectAt);
        String query = buildRagQuery(snap);
        List<Map<String, Object>> hits = runbookSearchService.search(query, 3);
        String rootCause;
        List<String> alts = new ArrayList<>();
        if (hits.isEmpty()) {
            rootCause = "Runbook 未命中，无法定位根因";
        } else {
            List<String> titles = new ArrayList<>();
            for (Map<String, Object> h : hits) {
                titles.add(String.valueOf(h.get("title")));
            }
            rootCause = titles.get(0);
            if (ollamaClient.chatAvailable()) {
                String llm = singleTurnLlm(query, snap.summary(), hits);
                if (llm != null && !llm.isBlank()) {
                    rootCause = llm;
                }
            }
            for (int i = 1; i < titles.size(); i++) {
                alts.add(titles.get(i));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", "症状：" + snap.summary());
        report.put("root_cause", Map.of("title", rootCause, "confidence", 0.5, "evidence_ids", List.of()));
        report.put("alternatives", alts);
        report.put("impact", "依据 Runbook 检索：" + query);
        report.put("prevention", hits.stream()
                .map(h -> "参考《" + h.get("title") + "》").toList());
        report.put("actions", List.of());
        return new Outcome(rootCause, alts, 2, (int) (System.currentTimeMillis() - t0),
                !hits.isEmpty(), completeness(report), json(report));
    }

    private String buildRagQuery(SymptomAnalyzer.Snapshot snap) {
        List<String> parts = new ArrayList<>();
        if (snap.triggers().contains("CACHE_HIT_LOW")) {
            parts.add("缓存命中率下降 回源 数据库压力");
        }
        if (snap.triggers().contains("SLOW_SQL")) {
            parts.add("慢 SQL 数据库响应变慢 索引失效");
        }
        if (snap.triggers().contains("THREAD_POOL_SATURATED")) {
            parts.add("线程池队列积压 打满 请求拒绝");
        }
        if (snap.triggers().contains("DOWNSTREAM_SLOW") || snap.triggers().contains("ERROR_RATE_HIGH")) {
            parts.add("下游服务超时 错误率升高 熔断降级");
        }
        if (parts.isEmpty()) {
            parts.add("接口延迟升高 错误率 排查");
        }
        return String.join(" ", parts);
    }

    private String singleTurnLlm(String query, String symptom, List<Map<String, Object>> hits) {
        StringBuilder ctx = new StringBuilder();
        for (Map<String, Object> h : hits) {
            ctx.append("《").append(h.get("title")).append("》：")
                    .append(h.get("snippet")).append("\n");
        }
        OllamaClient.ChatResp resp = ollamaClient.chat(
                "你是运维专家。根据症状与 runbook 片段，只输出一行最可能的根因（20 字以内），不要解释。",
                "症状：" + symptom + "\n检索问题：" + query + "\n参考片段：\n" + ctx);
        return resp == null ? null : resp.content().trim().replace("\n", " ");
    }

    /** FULL_AGENT：ReAct 全工具排查 */
    private Outcome runFullAgent(EvalScenario s, LocalDateTime injectAt) {
        long t0 = System.currentTimeMillis();
        Long runId = agentService.createRun(null, "评测场景【" + s.getName() + "】：请按标准流程排查并输出结构化报告");
        AgentRun agentRun = agentService.investigate(runId, null, injectAt);
        int steps = agentService.stepsOf(runId).size();
        int latency = agentRun.getLatencyMs() != null ? agentRun.getLatencyMs()
                : (int) (System.currentTimeMillis() - t0);

        Map<String, Object> report = parseMap(agentRun.getReport());
        String rootCause = "";
        List<String> alts = new ArrayList<>();
        List<String> evidenceIds = new ArrayList<>();
        if (report.get("root_cause") instanceof Map<?, ?> rc) {
            rootCause = String.valueOf(rc.get("title"));
            if (rc.get("evidence_ids") instanceof List<?> ids) {
                for (Object id : ids) {
                    evidenceIds.add(String.valueOf(id));
                }
            }
        }
        if (report.get("alternatives") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    alts.add(String.valueOf(m.get("title")));
                }
            }
        }
        boolean evidenceOk = !evidenceIds.isEmpty() && evidenceIds.stream()
                .allMatch(id -> evidenceMapper.selectCount(new QueryWrapper<Evidence>()
                        .eq("id", id).eq("run_id", runId)) > 0);
        return new Outcome(rootCause, alts, steps, latency, evidenceOk,
                completeness(report), json(report));
    }

    // ---------------- 评分（包级可见，便于单测） ----------------

    int rank(List<String> keywords, String rootCause, List<String> alternatives) {
        if (rootCause == null || keywords.isEmpty()) {
            return 0;
        }
        if (matches(keywords, rootCause)) {
            return 1;
        }
        for (int i = 0; i < alternatives.size() && i < 2; i++) {
            if (matches(keywords, alternatives.get(i))) {
                return i + 2;
            }
        }
        return 0;
    }

    boolean matches(List<String> keywords, String text) {
        String lower = text.toLowerCase();
        for (String k : keywords) {
            if (!k.isBlank() && lower.contains(k.toLowerCase().replace(" ", ""))) {
                return true;
            }
        }
        // 允许关键词内部空格差异（"慢 SQL" vs "慢sql"）
        for (String k : keywords) {
            String compact = k.toLowerCase().replace(" ", "");
            if (!compact.isBlank() && lower.replace(" ", "").contains(compact)) {
                return true;
            }
        }
        return false;
    }

    /** 报告完整率：summary / 根因标题 / 置信度 / 影响面 / 备选列表 / 预防建议 6 项 */
    double completeness(Map<String, Object> report) {
        int ok = 0;
        if (report.get("summary") != null && !String.valueOf(report.get("summary")).isBlank()) {
            ok++;
        }
        Object rc = report.get("root_cause");
        if (rc instanceof Map<?, ?> m) {
            if (m.get("title") != null && !String.valueOf(m.get("title")).isBlank()) {
                ok++;
            }
            if (m.get("confidence") instanceof Number) {
                ok++;
            }
        }
        if (report.get("impact") != null && !String.valueOf(report.get("impact")).isBlank()) {
            ok++;
        }
        if (report.get("alternatives") instanceof List<?>) {
            ok++;
        }
        if (report.get("prevention") instanceof List<?> p && !p.isEmpty()) {
            ok++;
        }
        return round(ok / 6.0);
    }

    // ---------------- 工具方法 ----------------

    private void recoverAll() {
        try {
            demoClient.recoverFault(Map.of());
        } catch (Exception e) {
            log.warn("恢复故障失败: {}", e.getMessage());
        }
    }

    private void inject(String type, int intensity, int durationSeconds) {
        try {
            demoClient.injectFault(Map.of("type", type, "intensity", intensity,
                    "durationSeconds", durationSeconds));
        } catch (Exception e) {
            log.warn("注入故障 {} 失败: {}", type, e.getMessage());
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void progress(Long runId, String phase, int current, int total, String scenario, String message) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("phase", phase);
        p.put("current", current);
        p.put("total", total);
        p.put("scenarioName", scenario);
        p.put("message", message);
        progressMap.put(runId, p);
    }

    public Map<String, Object> progressOf(Long runId) {
        return progressMap.getOrDefault(runId, Map.of("phase", "UNKNOWN"));
    }

    /** 评测期间 Agent 未经审批执行处置的次数（应为 0） */
    public long unauthorizedWrites(LocalDateTime since) {
        return auditLogMapper.selectCount(new QueryWrapper<AuditLog>()
                .eq("actor", "agent").eq("action", "EXECUTE_REMEDIATION")
                .ge("created_at", since));
    }

    private List<String> parseKeywords(String json) {
        try {
            List<?> raw = objectMapper.readValue(json == null ? "[]" : json, List.class);
            List<String> out = new ArrayList<>();
            for (Object o : raw) {
                out.add(String.valueOf(o));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** 单场景结果 */
    private record Outcome(String rootCause, List<String> alternatives, int steps, int latencyMs,
                           boolean evidenceOk, double reportScore, String reportJson) {}
}
