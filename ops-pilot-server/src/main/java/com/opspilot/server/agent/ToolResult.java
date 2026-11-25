package com.opspilot.server.agent;

/**
 * 工具执行结果：summary 给模型看，data 为结构化 payload，evidenceId 关联证据编号。
 */
public record ToolResult(boolean success, String summary, Object data, String evidenceId) {

    public static ToolResult ok(String summary, Object data, String evidenceId) {
        return new ToolResult(true, summary, data, evidenceId);
    }

    public static ToolResult fail(String summary) {
        return new ToolResult(false, summary, null, null);
    }
}
