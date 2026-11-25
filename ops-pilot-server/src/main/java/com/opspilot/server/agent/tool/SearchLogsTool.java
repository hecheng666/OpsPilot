package com.opspilot.server.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.Tool;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.entity.LogEntry;
import com.opspilot.server.mapper.LogEntryMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务日志：按 pattern 聚合计数，附样本 message 与 traceId。
 */
@Component
public class SearchLogsTool implements Tool {

    private final LogEntryMapper logEntryMapper;
    private final EvidenceService evidenceService;

    @Value("${opspilot.service-name}")
    private String defaultService;

    public SearchLogsTool(LogEntryMapper logEntryMapper, EvidenceService evidenceService) {
        this.logEntryMapper = logEntryMapper;
        this.evidenceService = evidenceService;
    }

    @Override
    public String name() {
        return "search_logs";
    }

    @Override
    public String description() {
        return "检索服务日志，按 pattern 聚合计数并给出样本与 traceId，可按关键词/级别过滤";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "服务名，默认当前被观测服务"),
                        "keyword", Map.of("type", "string", "description", "关键词，匹配 message 或 pattern"),
                        "level", Map.of("type", "string", "description", "级别 ERROR/WARN/INFO"),
                        "windowMinutes", Map.of("type", "integer", "description", "时间窗口分钟数，1-60，默认 30"),
                        "limit", Map.of("type", "integer", "description", "最大条数，1-200，默认 100")),
                "required", List.of());
    }

    @Override
    public String riskLevel() {
        return "READ";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String service = ToolArgs.str(args, "service", defaultService);
        String keyword = ToolArgs.str(args, "keyword", null);
        String level = ToolArgs.str(args, "level", null);
        int window = ToolArgs.intVal(args, "windowMinutes", 30, 60);
        int limit = ToolArgs.intVal(args, "limit", 100, 200);
        LocalDateTime since = ctx.since() != null ? ctx.since() : LocalDateTime.now().minusMinutes(window);

        QueryWrapper<LogEntry> q = new QueryWrapper<LogEntry>()
                .eq("service", service).ge("ts", since)
                .orderByDesc("ts").last("LIMIT " + limit);
        if (level != null) {
            q.eq("level", level);
        }
        if (keyword != null) {
            q.and(w -> w.like("message", keyword).or().like("pattern", keyword));
        }
        List<LogEntry> logs = logEntryMapper.selectList(q);

        // 按 pattern 聚合
        Map<String, Map<String, Object>> byPattern = new LinkedHashMap<>();
        for (LogEntry l : logs) {
            String pattern = l.getPattern() == null ? "UNKNOWN" : l.getPattern();
            Map<String, Object> agg = byPattern.computeIfAbsent(pattern, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pattern", pattern);
                m.put("count", 0);
                m.put("sampleMessage", l.getMessage());
                m.put("traceIds", new ArrayList<String>());
                return m;
            });
            agg.put("count", (int) agg.get("count") + 1);
            @SuppressWarnings("unchecked")
            List<String> traceIds = (List<String>) agg.get("traceIds");
            if (l.getTraceId() != null && traceIds.size() < 3 && !traceIds.contains(l.getTraceId())) {
                traceIds.add(l.getTraceId());
            }
        }
        List<Map<String, Object>> patterns = new ArrayList<>(byPattern.values());
        patterns.sort((a, b) -> Integer.compare((int) b.get("count"), (int) a.get("count")));
        if (patterns.size() > 10) {
            patterns = patterns.subList(0, 10);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("windowMinutes", window);
        data.put("total", logs.size());
        data.put("patterns", patterns);

        String summary = logs.isEmpty()
                ? "最近" + window + "分钟无匹配日志"
                : "最近%d分钟 %d 条日志，TOP pattern：%s".formatted(window, logs.size(),
                        patterns.isEmpty() ? "无" : patterns.get(0).get("pattern") + "×" + patterns.get(0).get("count"));
        String eid = evidenceService.create(ctx.runIdOrZero(), "LOG",
                service + "/" + (keyword == null ? "all" : keyword),
                "日志检索 " + (keyword == null ? "全部" : keyword), summary, data);
        return ToolResult.ok(summary, data, eid);
    }
}
