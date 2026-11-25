package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evidence")
public class Evidence {
    /** 业务可读编号，如 E-1001 */
    @TableId
    private String id;
    private Long runId;
    /** METRIC / LOG / CHANGE / RUNBOOK / TOPOLOGY / INCIDENT / ACTION */
    private String sourceType;
    private String sourceId;
    private String title;
    private String summary;
    private String payload;
    private LocalDateTime createdAt;
}
