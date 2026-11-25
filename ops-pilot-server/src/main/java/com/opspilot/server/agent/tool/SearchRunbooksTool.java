package com.opspilot.server.agent.tool;

import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.Tool;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.rag.RunbookSearchService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runbook 混合检索工具：关键词 + 向量 RRF 融合，返回可引用的片段。
 */
@Component
public class SearchRunbooksTool implements Tool {

    private final RunbookSearchService searchService;
    private final EvidenceService evidenceService;

    public SearchRunbooksTool(RunbookSearchService searchService, EvidenceService evidenceService) {
        this.searchService = searchService;
        this.evidenceService = evidenceService;
    }

    @Override
    public String name() {
        return "search_runbooks";
    }

    @Override
    public String description() {
        return "检索运维 runbook 知识库（慢 SQL、缓存、线程池、下游超时等处置手册），返回相关片段与引用";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "检索问题，如「缓存命中率下降」，必填"),
                        "topK", Map.of("type", "integer", "description", "返回条数，1-6，默认 5")),
                "required", List.of("query"));
    }

    @Override
    public String riskLevel() {
        return "READ";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String query = ToolArgs.str(args, "query", null);
        if (query == null) {
            return ToolResult.fail("缺少必填参数 query");
        }
        int topK = ToolArgs.intVal(args, "topK", 5, 6);
        List<Map<String, Object>> results = searchService.search(query, topK);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("total", results.size());
        data.put("results", results);

        String summary = results.isEmpty()
                ? "runbook 未命中：" + query
                : "命中 %d 个片段：%s".formatted(results.size(), results.stream()
                        .map(r -> "《" + r.get("title") + "》")
                        .distinct()
                        .collect(Collectors.joining("、")));
        String sourceId = results.stream()
                .map(r -> String.valueOf(r.get("chunkId")))
                .collect(Collectors.joining(","));
        String eid = evidenceService.create(ctx.runIdOrZero(), "RUNBOOK", sourceId.isEmpty() ? query : sourceId,
                "Runbook 检索：" + query, summary, data);
        return ToolResult.ok(summary, data, eid);
    }
}
