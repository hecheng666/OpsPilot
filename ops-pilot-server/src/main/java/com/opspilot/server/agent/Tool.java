package com.opspilot.server.agent;

import java.util.Map;

/**
 * Agent 工具契约。所有工具参数有上限约束；写操作只创建审批单，执行必须经人工批准。
 */
public interface Tool {

    /** 工具名（snake_case），模型调用与注册表查找依据 */
    String name();

    /** 给模型看的中文描述 */
    String description();

    /** 参数 JSON Schema（简化版，供 prompt 与前端展示） */
    Map<String, Object> parametersSchema();

    /** READ / WRITE / CRITICAL */
    String riskLevel();

    ToolResult execute(Map<String, Object> args, ToolContext ctx);
}
