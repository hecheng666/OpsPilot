package com.opspilot.server.controller;

import com.opspilot.server.agent.Tool;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolRegistry;
import com.opspilot.server.agent.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具查看与手动执行（联调用）。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    @Value("${opspilot.service-name}")
    private String serviceName;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return toolRegistry.describeAll();
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Object nameObj = body.get("name");
        Tool tool = nameObj == null ? null : toolRegistry.get(String.valueOf(nameObj));
        if (tool == null) {
            resp.put("success", false);
            resp.put("summary", "未知工具: " + nameObj);
            return resp;
        }
        Object argsObj = body.get("args");
        Map<String, Object> args = argsObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        Long incidentId = args.get("incidentId") instanceof Number n ? n.longValue() : null;
        // 手动调用 runId=0，仅做联调验证
        ToolContext ctx = ToolContext.of(0L, incidentId, serviceName);
        try {
            ToolResult r = tool.execute(args, ctx);
            resp.put("success", r.success());
            resp.put("summary", r.summary());
            resp.put("data", r.data());
            resp.put("evidenceId", r.evidenceId());
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("summary", "工具执行异常: " + e.getMessage());
        }
        return resp;
    }
}
