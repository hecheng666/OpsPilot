package com.opspilot.server.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.server.entity.AgentRun;
import com.opspilot.server.entity.AgentStep;
import com.opspilot.server.entity.Incident;
import com.opspilot.server.eval.SymptomAnalyzer;
import com.opspilot.server.mapper.AgentRunMapper;
import com.opspilot.server.mapper.AgentStepMapper;
import com.opspilot.server.mapper.IncidentMapper;
import com.opspilot.server.rag.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 排查运行时（自研 ReAct 循环）：
 * 最多 6 步；每步模型输出 JSON（调工具 or 给报告）；工具结果生成证据（E-xxxx）；
 * 模型不调工具直接给报告时强制基线排查；Ollama 不可用时规则引擎出报告（degraded=1）。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final int MAX_STEPS = 6;

    /** ReAct 协议提示词：每轮输出一个 JSON，要么调工具，要么给最终报告 */
    private static final String SYSTEM_PROMPT = """
            你是 OpsPilot 值班 Agent，负责排查 Spring Boot 服务故障，给出根因与处置建议。
            你可以调用以下工具（每轮只能调用一个）：
            1. query_metrics {"metric":"p95","windowMinutes":30} — 查指标时序，metric 可选 qps/p95/errorRate/dbRtMs/cacheHitRate/threadActive/threadQueue/threadRejected/downstreamRtMs
            2. search_logs {"keyword":"SLOW","windowMinutes":30} — 检索日志，按 pattern 聚合
            3. get_recent_changes {"hours":24} — 查最近变更
            4. get_service_topology {} — 查依赖拓扑与各依赖健康
            5. search_runbooks {"query":"..."} — 检索运维手册
            6. get_incident_context {"incidentId":N} — 查告警上下文与当前故障注入状态
            7. propose_remediation {"actionType":"...","reason":"..."} — 提议处置（仅创建审批单），动作类型 RECOVER_FAULT/ENABLE_RATE_LIMIT/CLEAR_CACHE/ROLLBACK_CONFIG/SCALE_THREAD_POOL

            【输出格式】每轮必须且只能输出一个 JSON 对象，前后不要有任何其他文字：
            - 调用工具：{"thought":"你的分析","action":"工具名","args":{...}}
            - 最终报告：{"thought":"...","final":{报告}}
            报告 JSON 格式：
            {"summary":"现象一句话","root_cause":{"title":"根因","confidence":0.0到1.0,"evidence_ids":["E-xxxx"]},"alternatives":[{"title":"...","confidence":0.0,"evidence_ids":[]}],"impact":"影响面","actions":[{"actionId":数字,"risk":"LOW/MEDIUM/HIGH"}],"prevention":["预防建议"]}
            要求：必须先调工具收集证据（工具会返回 E-xxxx 编号），最终报告必须引用真实证据编号；根因标题必须包含具体机制关键词（慢 SQL/慢查询/缓存/线程池/下游超时），禁止只写「过载」「异常」；需要处置时先调 propose_remediation 拿到 actionId 再写进报告。
            纪律：
            - 工具名必须严格取自上面 7 个名字，禁止自造工具；
            - 未提供 incidentId 时禁止调用 get_incident_context；
            - 同一个工具最多调用 2 次，禁止用相同参数重复调用，被拦截后请换工具或直接输出 final；
            - 最多 6 步，已收集到 3 条以上证据后应尽快输出 final。
            判因要点：
            - 慢 SQL 看 dbRtMs 与 SLOW_QUERY 日志；
            - 缓存问题看 cacheHitRate 与 CACHE_MISS/CACHE_HIT_LOW 日志；
            - 线程池看 threadActive/threadQueue/threadRejected；
            - 下游超时看 downstreamRtMs 升高、错误率上升，并用依赖拓扑定位异常依赖，不要把下游问题误判为慢 SQL。
            示例（调用）：{"thought":"先看延迟","action":"query_metrics","args":{"metric":"p95","windowMinutes":30}}
            示例（收尾）：{"thought":"证据已足够","final":{"summary":"...","root_cause":{"title":"...","confidence":0.8,"evidence_ids":["E-1001"]},"alternatives":[],"impact":"...","actions":[],"prevention":["..."]}}
            """;

    /** 收尾提示词：不再暴露工具菜单，只要求基于已有证据产出 final 报告 */
    private static final String FINAL_PROMPT = """
            你是故障复盘报告生成器。根据排查记录输出且仅输出一个 JSON 对象：
            {"final":{"summary":"现象一句话","root_cause":{"title":"根因","confidence":0.8,"evidence_ids":["E-1001"]},"alternatives":[{"title":"备选根因","confidence":0.2,"evidence_ids":[]}],"impact":"影响面（必填：受影响的接口与用户）","actions":[],"prevention":["至少一条预防建议"]}}
            硬性要求：
            - 禁止输出 action 字段，禁止调用工具；
            - root_cause.title 必须包含具体机制关键词之一（慢 SQL/慢查询/缓存/线程池/下游超时），禁止只写「过载」「异常」等笼统表述；依据排查记录中的指标与日志区分，禁止臆断；
            - root_cause.evidence_ids 必须至少包含 1 个本次排查记录中出现过的 E-xxxx 编号，禁止编造；
            - impact 必须非空，prevention 至少 1 条，alternatives 至少 1 条，actions 保持空数组。
            """;

    private final ToolRegistry toolRegistry;
    private final OllamaClient ollamaClient;
    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final IncidentMapper incidentMapper;
    private final SymptomAnalyzer symptomAnalyzer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${opspilot.service-name}")
    private String serviceName;

    public AgentService(ToolRegistry toolRegistry, OllamaClient ollamaClient,
                        AgentRunMapper runMapper, AgentStepMapper stepMapper,
                        IncidentMapper incidentMapper, SymptomAnalyzer symptomAnalyzer) {
        this.toolRegistry = toolRegistry;
        this.ollamaClient = ollamaClient;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.incidentMapper = incidentMapper;
        this.symptomAnalyzer = symptomAnalyzer;
    }

    /** 创建一次排查运行，返回 runId */
    public Long createRun(Long incidentId, String input) {
        AgentRun run = new AgentRun();
        run.setIncidentId(incidentId);
        run.setType("INVESTIGATE");
        run.setInput(input == null ? "" : input);
        run.setStatus("RUNNING");
        run.setDegraded(0);
        run.setCreatedAt(LocalDateTime.now());
        runMapper.insert(run);
        return run.getId();
    }

    /** 同步执行完整排查；emitter 非空时逐步推送 SSE */
    public AgentRun investigate(Long runId, SseEmitter emitter) {
        return investigate(runId, emitter, null);
    }

    /** since 非空时限定工具查询起点（评测隔离用） */
    public AgentRun investigate(Long runId, SseEmitter emitter, LocalDateTime since) {
        long start = System.currentTimeMillis();
        AgentRun run = runMapper.selectById(runId);
        ToolContext ctx = new ToolContext(runId, run.getIncidentId(), serviceName, since);
        List<String> history = new ArrayList<>();
        Set<String> runEvidence = new LinkedHashSet<>();
        int promptTokens = 0, completionTokens = 0;
        boolean degraded = false;
        Map<String, Object> report = null;

        try {
            if (!ollamaClient.chatAvailable()) {
                // 降级：规则引擎
                degraded = true;
                log.warn("chat 模型不可用，run #{} 走规则引擎降级", runId);
                report = degradedInvestigate(runId, ctx, emitter, runEvidence);
            } else {
                int consecParseFail = 0;
                int consecModelFail = 0;
                Set<String> calledKeys = new HashSet<>();
                Map<String, Integer> toolCallCount = new HashMap<>();
                for (int step = 1; step <= MAX_STEPS; step++) {
                    OllamaClient.ChatResp resp = ollamaClient.chat(SYSTEM_PROMPT,
                            buildUserPrompt(run, history));
                    if (resp == null) {
                        // 单次失败（如偶发超时/重复惩罚中断）先重试，连续两次才降级
                        consecModelFail++;
                        emit(emitter, "step", Map.of("stepNo", step, "type", "THOUGHT",
                                "summary", "模型调用失败，准备重试或降级", "status", "FAIL"));
                        history.add("第" + step + "步：模型调用失败，请重新给出 JSON 决策");
                        if (consecModelFail >= 3) {
                            degraded = true;
                            report = degradedInvestigate(runId, ctx, emitter, runEvidence);
                            break;
                        }
                        continue;
                    }
                    consecModelFail = 0;
                    promptTokens += resp.promptTokens();
                    completionTokens += resp.completionTokens();

                    JsonNode decision = parseJson(resp.content());
                    if (decision == null || (!decision.has("action") && !decision.has("final"))) {
                        consecParseFail++;
                        recordStep(runId, step, "THOUGHT", null, null,
                                "模型输出无法解析：" + truncate(resp.content(), 200), null, "FAIL", 0);
                        emit(emitter, "step", Map.of("stepNo", step, "type", "THOUGHT",
                                "summary", "模型输出格式错误，要求重试", "status", "FAIL"));
                        history.add("第" + step + "步：你的输出不是合法 JSON，请严格按格式重新输出");
                        if (consecParseFail >= 2) {
                            degraded = true;
                            report = degradedInvestigate(runId, ctx, emitter, runEvidence);
                            break;
                        }
                        continue;
                    }
                    consecParseFail = 0;
                    String thought = decision.path("thought").asText("");

                    if (decision.has("final")) {
                        if (runEvidence.isEmpty() && step < MAX_STEPS) {
                            // 模型未调工具就下结论：强制基线排查
                            emit(emitter, "step", Map.of("stepNo", step, "type", "THOUGHT",
                                    "summary", "尚无证据，强制基线排查", "status", "OK"));
                            baselineCollect(runId, ctx, history, runEvidence, emitter, step);
                            continue;
                        }
                        report = normalizeReport(decision.path("final"), runEvidence);
                        recordStep(runId, step, "REPORT", null, null,
                                truncate(thought, 500), null, "OK", 0);
                        break;
                    }

                    // 工具调用
                    String toolName = decision.path("action").asText("");
                    Map<String, Object> toolArgs = new LinkedHashMap<>();
                    if (decision.get("args") != null && decision.get("args").isObject()) {
                        toolArgs = objectMapper.convertValue(decision.get("args"), Map.class);
                    }
                    String callKey = toolName + objectMapper.writeValueAsString(toolArgs);
                    boolean duplicate = !calledKeys.add(callKey);
                    int used = toolCallCount.merge(toolName, 1, Integer::sum);
                    boolean overused = used > 2;
                    boolean forceFinish = false;
                    Tool tool = toolRegistry.get(toolName);
                    long t0 = System.currentTimeMillis();
                    ToolResult result;
                    if (duplicate || overused) {
                        forceFinish = true;
                        result = ToolResult.fail(overused
                                ? "工具 " + toolName + " 已调用 2 次，请更换工具或直接输出 final"
                                : "已拦截重复调用（相同工具与参数），请更换工具或直接输出 final");
                    } else if (tool == null) {
                        result = ToolResult.fail("未知工具: " + toolName);
                    } else if ("execute_remediation".equals(toolName)) {
                        // Agent 不允许直接执行处置，引导走审批
                        result = ToolResult.fail("Agent 无权执行处置动作，请用 propose_remediation 提议");
                    } else {
                        if (toolArgs.get("incidentId") == null && ctx.incidentId() != null) {
                            toolArgs.put("incidentId", ctx.incidentId());
                        }
                        try {
                            result = tool.execute(toolArgs, ctx);
                        } catch (Exception e) {
                            result = ToolResult.fail("工具异常: " + e.getMessage());
                        }
                    }
                    int elapsed = (int) (System.currentTimeMillis() - t0);
                    if (result.evidenceId() != null) {
                        runEvidence.add(result.evidenceId());
                    }
                    recordStep(runId, step, "TOOL_CALL", toolName,
                            truncate(objectMapper.writeValueAsString(toolArgs), 500),
                            truncate(result.summary(), 500), result.evidenceId(),
                            result.success() ? "OK" : "FAIL", elapsed);
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("stepNo", step);
                    ev.put("type", "TOOL_CALL");
                    ev.put("toolName", toolName);
                    ev.put("summary", result.summary());
                    ev.put("status", result.success() ? "OK" : "FAIL");
                    if (result.evidenceId() != null) {
                        ev.put("evidenceId", result.evidenceId());
                    }
                    ev.put("elapsedMs", elapsed);
                    emit(emitter, "step", ev);
                    history.add("第%d步 [%s] %s → %s%s".formatted(step, toolName,
                            truncate(objectMapper.writeValueAsString(toolArgs), 120),
                            result.summary(),
                            result.evidenceId() == null ? "" : "（证据 " + result.evidenceId() + "）"));
                    if (forceFinish) {
                        break;
                    }
                }
                if (report == null && !degraded) {
                    // 步数用完：用「只准输出 final」的强化提示收尾，最多两次，仍失败则规则兜底
                    history.add("步数已用完，必须立即输出最终报告（final）");
                    String finalSystem = FINAL_PROMPT;
                    String finalUser = buildUserPrompt(run, history)
                            + (runEvidence.isEmpty() ? ""
                            : "\n本次可用证据编号（根因至少引用 1 个）：" + String.join("、", runEvidence));
                    for (int attempt = 1; attempt <= 2 && report == null; attempt++) {
                        OllamaClient.ChatResp last = ollamaClient.chat(finalSystem, finalUser);
                        if (last == null) {
                            break;
                        }
                        promptTokens += last.promptTokens();
                        completionTokens += last.completionTokens();
                        JsonNode d = parseJson(last.content());
                        if (d != null && d.has("final")) {
                            report = normalizeReport(d.path("final"), runEvidence);
                        } else {
                            finalUser = finalUser
                                    + "\n上次输出无效：必须只输出 {\"final\":{...}}，不允许出现 action 字段。";
                        }
                    }
                    if (report == null) {
                        degraded = true;
                        report = ruleBasedReport(runId, ctx, history, runEvidence);
                    }
                }
            }

            String reportJson = objectMapper.writeValueAsString(report);
            run.setStatus("DONE");
            run.setReport(reportJson);
            run.setPromptTokens(promptTokens);
            run.setCompletionTokens(completionTokens);
            run.setLatencyMs((int) (System.currentTimeMillis() - start));
            run.setDegraded(degraded ? 1 : 0);
            runMapper.updateById(run);
            backfillIncident(run.getIncidentId(), report, reportJson);
            emit(emitter, "report", report);
            emit(emitter, "done", Map.of("runId", runId, "status", "DONE", "degraded", degraded));
        } catch (Exception e) {
            log.error("run #{} 排查异常", runId, e);
            run.setStatus("FAILED");
            run.setLatencyMs((int) (System.currentTimeMillis() - start));
            runMapper.updateById(run);
            emit(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
        }
        return runMapper.selectById(runId);
    }

    /** 基线排查：固定调用 metrics×2 + logs + changes + runbooks，结果进 history 并产证据 */
    private void baselineCollect(Long runId, ToolContext ctx, List<String> history,
                                 Set<String> runEvidence, SseEmitter emitter, int stepNo) {
        String[][] calls = {
                {"query_metrics", "{\"metric\":\"p95\",\"windowMinutes\":30}"},
                {"query_metrics", "{\"metric\":\"dbRtMs\",\"windowMinutes\":30}"},
                {"query_metrics", "{\"metric\":\"cacheHitRate\",\"windowMinutes\":30}"},
                {"search_logs", "{\"windowMinutes\":30,\"limit\":100}"},
                {"get_recent_changes", "{\"hours\":24}"},
                {"search_runbooks", "{\"query\":\"接口延迟升高 错误率 排查\",\"topK\":4}"}
        };
        for (String[] c : calls) {
            Tool tool = toolRegistry.get(c[0]);
            try {
                Map<String, Object> args = objectMapper.readValue(c[1], Map.class);
                if (ctx.incidentId() != null) {
                    args.putIfAbsent("incidentId", ctx.incidentId());
                }
                ToolResult r = tool.execute(args, ctx);
                if (r.evidenceId() != null) {
                    runEvidence.add(r.evidenceId());
                }
                recordStep(runId, stepNo, "TOOL_CALL", c[0], c[1],
                        truncate(r.summary(), 500), r.evidenceId(), r.success() ? "OK" : "FAIL", 0);
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("stepNo", stepNo);
                ev.put("type", "TOOL_CALL");
                ev.put("toolName", c[0]);
                ev.put("summary", r.summary());
                ev.put("status", r.success() ? "OK" : "FAIL");
                if (r.evidenceId() != null) {
                    ev.put("evidenceId", r.evidenceId());
                }
                emit(emitter, "step", ev);
                history.add("基线 [%s] → %s%s".formatted(c[0], r.summary(),
                        r.evidenceId() == null ? "" : "（证据 " + r.evidenceId() + "）"));
            } catch (Exception e) {
                history.add("基线 [%s] → 失败: %s".formatted(c[0], e.getMessage()));
            }
        }
    }

    /** 降级入口：基线排查 + 规则引擎报告 */
    private Map<String, Object> degradedInvestigate(Long runId, ToolContext ctx, SseEmitter emitter,
                                                    Set<String> runEvidence) {
        List<String> history = new ArrayList<>();
        emit(emitter, "step", Map.of("stepNo", 0, "type", "THOUGHT",
                "summary", "模型不可用，执行规则引擎基线排查", "status", "OK"));
        baselineCollect(runId, ctx, history, runEvidence, emitter, 0);
        return ruleBasedReport(runId, ctx, history, runEvidence);
    }

    /** 规则引擎：仅依据已采集指标与日志做阈值/关键词推断（不读取故障真值），并提议动作 */
    private Map<String, Object> ruleBasedReport(Long runId, ToolContext ctx, List<String> history,
                                                Set<String> runEvidence) {
        SymptomAnalyzer.Snapshot snap = ctx.since() != null
                ? symptomAnalyzer.analyze(ctx.since())
                : symptomAnalyzer.analyze(10);
        String cause = snap.causeTitle();
        String actionType = snap.suggestedAction();
        double confidence = snap.triggers().isEmpty() ? 0.35 : Math.min(0.85, 0.55 + snap.triggers().size() * 0.1);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", "规则引擎诊断（模型不可用降级）：" + snap.summary());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", cause);
        root.put("confidence", confidence);
        root.put("evidence_ids", new ArrayList<>(runEvidence));
        report.put("root_cause", root);
        report.put("alternatives", List.of());
        report.put("impact", "触发规则：" + String.join("、", snap.triggers()));
        List<Map<String, Object>> actions = new ArrayList<>();
        if (actionType != null) {
            Tool propose = toolRegistry.get("propose_remediation");
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("actionType", actionType);
            args.put("reason", "规则引擎建议：" + cause);
            if (ctx.incidentId() != null) {
                args.put("incidentId", ctx.incidentId());
            }
            ToolResult r = propose.execute(args, ctx);
            if (r.success() && r.data() instanceof Map<?, ?> m) {
                actions.add(Map.of("actionId", ((Number) m.get("actionId")).longValue(),
                        "risk", String.valueOf(m.get("riskLevel")), "status", "PENDING_APPROVAL"));
            }
        }
        report.put("actions", actions);
        report.put("prevention", List.of("为核心指标配置阈值告警", "定期演练故障注入与处置流程"));
        Map<String, Object> normalized = normalizeReport(report, runEvidence);
        recordStep(runId, 0, "REPORT", null, null, "规则引擎出报告", null, "OK", 0);
        return normalized;
    }

    /** 报告契约兜底：缺字段补默认，证据编号只保留本 run 真实产生的 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeReport(JsonNode finalNode, Set<String> runEvidence) {
        Map<String, Object> raw;
        try {
            raw = objectMapper.convertValue(finalNode, Map.class);
        } catch (Exception e) {
            raw = new LinkedHashMap<>();
        }
        return normalizeReport(raw, runEvidence);
    }

    private Map<String, Object> normalizeReport(Map<String, Object> raw, Set<String> runEvidence) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", strOr(raw.get("summary"), "（模型未提供摘要）"));

        Object rc = raw.get("root_cause");
        Map<String, Object> root = new LinkedHashMap<>();
        if (rc instanceof Map<?, ?> m) {
            root.put("title", strOr(m.get("title"), "未知根因"));
            root.put("confidence", numOr(m.get("confidence"), 0.5));
            root.put("evidence_ids", filterEvidence(m.get("evidence_ids"), runEvidence));
        } else {
            root.put("title", strOr(rc, "未知根因"));
            root.put("confidence", 0.5);
            root.put("evidence_ids", new ArrayList<>(runEvidence));
        }
        out.put("root_cause", root);

        List<Map<String, Object>> alts = new ArrayList<>();
        if (raw.get("alternatives") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> alt = new LinkedHashMap<>();
                    alt.put("title", strOr(m.get("title"), ""));
                    alt.put("confidence", numOr(m.get("confidence"), 0.0));
                    alt.put("evidence_ids", filterEvidence(m.get("evidence_ids"), runEvidence));
                    alts.add(alt);
                }
            }
        }
        out.put("alternatives", alts);
        out.put("impact", strOr(raw.get("impact"), ""));

        List<Map<String, Object>> actions = new ArrayList<>();
        if (raw.get("actions") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("actionId", m.get("actionId") instanceof Number n ? n.longValue() : 0L);
                    a.put("risk", strOr(m.get("risk"), "MEDIUM"));
                    a.put("status", "PENDING_APPROVAL");
                    actions.add(a);
                }
            }
        }
        out.put("actions", actions);

        List<String> prevention = new ArrayList<>();
        if (raw.get("prevention") instanceof List<?> list) {
            for (Object o : list) {
                prevention.add(String.valueOf(o));
            }
        }
        out.put("prevention", prevention);
        return out;
    }

    /** 只保留本 run 真实产生的证据编号，防止模型编造 */
    private List<String> filterEvidence(Object ids, Set<String> runEvidence) {
        List<String> out = new ArrayList<>();
        if (ids instanceof List<?> list) {
            for (Object o : list) {
                String id = String.valueOf(o);
                if (runEvidence.contains(id) && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private void backfillIncident(Long incidentId, Map<String, Object> report, String reportJson) {
        if (incidentId == null) {
            return;
        }
        Incident incident = incidentMapper.selectById(incidentId);
        if (incident == null) {
            return;
        }
        Object rc = report.get("root_cause");
        if (rc instanceof Map<?, ?> m) {
            incident.setRootCause(String.valueOf(m.get("title")));
        }
        incident.setReport(reportJson);
        incidentMapper.updateById(incident);
    }

    private void recordStep(Long runId, int stepNo, String type, String toolName,
                            String args, String result, String evidenceId, String status, int elapsedMs) {
        AgentStep s = new AgentStep();
        s.setRunId(runId);
        s.setStepNo(stepNo);
        s.setType(type);
        s.setToolName(toolName);
        s.setArgs(args);
        s.setResult(result);
        s.setEvidenceId(evidenceId);
        s.setStatus(status);
        s.setElapsedMs(elapsedMs);
        s.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(s);
    }

    private String buildUserPrompt(AgentRun run, List<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：排查服务 ").append(serviceName).append(" 的异常。");
        if (run.getInput() != null && !run.getInput().isBlank()) {
            sb.append(run.getInput());
        }
        if (run.getIncidentId() != null) {
            sb.append("\n关联告警 incidentId=").append(run.getIncidentId())
                    .append("（可先调 get_incident_context）。");
        }
        if (history.isEmpty()) {
            sb.append("\n还没有任何排查记录，请开始第一步。");
        } else {
            sb.append("\n已完成步骤：\n");
            for (String h : history) {
                sb.append("- ").append(h).append("\n");
            }
            sb.append("请基于以上结果决定下一步。");
        }
        return sb.toString();
    }

    private JsonNode parseJson(String content) {
        try {
            int s = content.indexOf('{');
            int e = content.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return null;
            }
            return objectMapper.readTree(content.substring(s, e + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private void emit(SseEmitter emitter, String event, Object data) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (Exception ignored) {
            // 客户端断开
        }
    }

    private static String strOr(Object v, String def) {
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    private static double numOr(Object v, double def) {
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    /** 查询某 run 的全部步骤（回放用） */
    public List<AgentStep> stepsOf(Long runId) {
        return stepMapper.selectList(new QueryWrapper<AgentStep>()
                .eq("run_id", runId).orderByAsc("id"));
    }
}
