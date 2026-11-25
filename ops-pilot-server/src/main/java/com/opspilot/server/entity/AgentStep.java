package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_step")
public class AgentStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private Integer stepNo;
    /** THOUGHT / TOOL_CALL / TOOL_RESULT / REPORT */
    private String type;
    private String toolName;
    private String args;
    private String result;
    private String evidenceId;
    /** OK / FAIL */
    private String status;
    private Integer elapsedMs;
    private LocalDateTime createdAt;
}
