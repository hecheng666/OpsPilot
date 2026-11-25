package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("remediation_action")
public class RemediationAction {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
    private Long runId;
    private String actionType;
    private String target;
    private String params;
    private String riskLevel;
    private String reason;
    /** PENDING / APPROVED / REJECTED / EXECUTED / FAILED */
    private String status;
    private String result;
    private String createdBy;
    private LocalDateTime createdAt;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime executedAt;
}
