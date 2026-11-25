package com.opspilot.server.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolContextTest {

    @Test
    @DisplayName("人工审批执行时保留审批人")
    void ofActorKeepsActor() {
        ToolContext ctx = ToolContext.ofActor(1L, 67L, "order-demo", "admin");
        assertThat(ctx.actor()).isEqualTo("admin");
        assertThat(ctx.runIdOrZero()).isEqualTo(1L);
    }

    @Test
    @DisplayName("审批人为空时回退到 agent")
    void ofActorFallsBackToAgent() {
        assertThat(ToolContext.ofActor(1L, 67L, "order-demo", null).actor()).isEqualTo("agent");
        assertThat(ToolContext.ofActor(1L, 67L, "order-demo", "  ").actor()).isEqualTo("agent");
    }

    @Test
    @DisplayName("默认构造（Agent 自主调用）使用 agent")
    void defaultFactoriesUseAgent() {
        assertThat(ToolContext.of(1L, 67L, "order-demo").actor()).isEqualTo("agent");
        assertThat(new ToolContext(1L, 67L, "order-demo", null).actor()).isEqualTo("agent");
    }

    @Test
    @DisplayName("runId 为空时按 0 处理")
    void runIdOrZeroHandlesNull() {
        ToolContext ctx = new ToolContext(null, null, "order-demo", null, "agent");
        assertThat(ctx.runIdOrZero()).isZero();
    }
}
