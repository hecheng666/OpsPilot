package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eval_run")
public class EvalRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** RULE / RAG_ONLY / FULL_AGENT */
    private String baseline;
    /** RUNNING / DONE / FAILED */
    private String status;
    private Integer total;
    private Integer hitTop1;
    private Integer hitTop3;
    private Double avgSteps;
    private Double avgLatencyMs;
    private Double evidenceAccuracy;
    private Double reportCompleteness;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
