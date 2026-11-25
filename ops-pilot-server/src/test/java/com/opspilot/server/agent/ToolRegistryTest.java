package com.opspilot.server.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistryTest {

    @Test
    @DisplayName("按名查找工具，未注册返回 null")
    void getsByName() {
        Tool metrics = new FakeTool("query_metrics", "查指标", "READ");
        Tool execute = new FakeTool("execute_remediation", "执行处置", "CRITICAL");
        ToolRegistry registry = new ToolRegistry(List.of(metrics, execute));

        assertThat(registry.get("query_metrics")).isSameAs(metrics);
        assertThat(registry.get("execute_remediation")).isSameAs(execute);
        assertThat(registry.get("not_exists")).isNull();
    }

    @Test
    @DisplayName("describeAll 输出名称、描述、风险级别与参数 schema")
    void describesAllTools() {
        ToolRegistry registry = new ToolRegistry(List.of(
                new FakeTool("query_metrics", "查指标", "READ"),
                new FakeTool("execute_remediation", "执行处置", "CRITICAL")));

        List<Map<String, Object>> all = registry.describeAll();

        assertThat(all).hasSize(2);
        assertThat(all.get(1))
                .containsEntry("name", "execute_remediation")
                .containsEntry("description", "执行处置")
                .containsEntry("riskLevel", "CRITICAL")
                .containsKey("parameters");
        assertThat(all.get(1).get("parameters")).isInstanceOf(Map.class);
    }

    private static final class FakeTool implements Tool {
        private final String name;
        private final String description;
        private final String risk;

        private FakeTool(String name, String description, String risk) {
            this.name = name;
            this.description = description;
            this.risk = risk;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String riskLevel() {
            return risk;
        }

        @Override
        public ToolResult execute(Map<String, Object> args, ToolContext ctx) {
            return ToolResult.ok("ok", Map.of(), null);
        }
    }
}
