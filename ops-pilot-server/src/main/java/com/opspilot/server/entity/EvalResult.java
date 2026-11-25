package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("eval_result")
public class EvalResult {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long evalRunId;
    private Long scenarioId;
    private String detectedRootCause;
    /** 1=根因命中，2/3=备选命中，0=未命中 */
    private Integer hitRank;
    private Integer steps;
    private Integer latencyMs;
    private Integer evidenceOk;
    private Double reportScore;
    private String report;
}
