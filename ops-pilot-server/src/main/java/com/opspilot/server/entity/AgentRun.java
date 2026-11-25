package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_run")
public class AgentRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long incidentId;
    /** INVESTIGATE */
    private String type;
    private String input;
    /** RUNNING / DONE / FAILED */
    private String status;
    private String report;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latencyMs;
    /** 1 = 模型不可用，规则引擎降级出报告 */
    private Integer degraded;
    private LocalDateTime createdAt;
}
