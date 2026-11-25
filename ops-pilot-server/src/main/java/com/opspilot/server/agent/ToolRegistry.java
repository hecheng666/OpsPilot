package com.opspilot.server.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：Spring 注入全部 Tool 实现，按名查找。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolList) {
        for (Tool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : tools.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("riskLevel", t.riskLevel());
            m.put("parameters", t.parametersSchema());
            list.add(m);
        }
        return list;
    }
}
