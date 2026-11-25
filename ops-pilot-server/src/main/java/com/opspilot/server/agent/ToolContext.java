package com.opspilot.server.agent;

import java.time.LocalDateTime;

/**
 * 工具执行上下文。runId=0 表示人工通过 API 直接调用（非 Agent 运行）。
 * since 非空时限定查询起点（评测时用于隔离上一场景的故障数据）。
 */
public record ToolContext(Long runId, Long incidentId, String service, LocalDateTime since) {

    public Long runIdOrZero() {
        return runId == null ? 0L : runId;
    }

    public static ToolContext of(Long runId, Long incidentId, String service) {
        return new ToolContext(runId, incidentId, service, null);
    }
}
