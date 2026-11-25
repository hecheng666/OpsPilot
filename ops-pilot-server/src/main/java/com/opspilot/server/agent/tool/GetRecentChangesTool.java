package com.opspilot.server.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.Tool;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.entity.ChangeEvent;
import com.opspilot.server.mapper.ChangeEventMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询最近变更事件（发布/配置/SQL 变更）。
 */
@Component
public class GetRecentChangesTool implements Tool {

    private final ChangeEventMapper changeEventMapper;
    private final EvidenceService evidenceService;

    @Value("${opspilot.service-name}")
    private String defaultService;

    public GetRecentChangesTool(ChangeEventMapper changeEventMapper, EvidenceService evidenceService) {
        this.changeEventMapper = changeEventMapper;
        this.evidenceService = evidenceService;
    }

    @Override
    public String name() {
        return "get_recent_changes";
    }

    @Override
    public String description() {
        return "查询服务最近的变更事件（发布、配置、SQL 变更），用于判断故障是否与变更相关";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "service", Map.of("type", "string", "description", "服务名，默认当前被观测服务"),
                        "hours", Map.of("type", "integer", "description", "回溯小时数，1-72，默认 24")),
                "required", List.of());
    }

    @Override
    public String riskLevel() {
        return "READ";
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
        String service = ToolArgs.str(args, "service", defaultService);
        int hours = ToolArgs.intVal(args, "hours", 24, 72);
        LocalDateTime since = ctx.since() != null ? ctx.since() : LocalDateTime.now().minusHours(hours);

        List<ChangeEvent> events = changeEventMapper.selectList(new QueryWrapper<ChangeEvent>()
                .eq("service", service).ge("created_at", since)
                .orderByDesc("created_at").last("LIMIT 100"));

        List<Map<String, Object>> items = new ArrayList<>();
        for (ChangeEvent e : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("type", e.getType());
            m.put("title", e.getTitle());
            m.put("detail", e.getDetail());
            m.put("operator", e.getOperator());
            m.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
            items.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", service);
        data.put("hours", hours);
        data.put("total", items.size());
        data.put("changes", items);

        String summary = items.isEmpty()
                ? "最近" + hours + "小时无变更事件"
                : "最近%d小时 %d 条变更，最新：%s".formatted(hours, items.size(), items.get(0).get("title"));
        String eid = evidenceService.create(ctx.runIdOrZero(), "CHANGE", service,
                "最近" + hours + "小时变更", summary, data);
        return ToolResult.ok(summary, data, eid);
    }
}
