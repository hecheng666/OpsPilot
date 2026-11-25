package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("eval_scenario")
public class EvalScenario {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String faultType;
    /** JSON：{"intensity":6,"durationSeconds":90} */
    private String config;
    /** 关键词数组 JSON，如 ["索引","慢查询","深分页"] */
    private String expectedRootCause;
    private String expectedAction;
    private Integer enabled;
}
