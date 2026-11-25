package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("runbook_chunk")
public class RunbookChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runbookId;
    private Integer chunkNo;
    private String content;
    private String model;
    private Integer dim;
    /** 向量 JSON 数组字符串 */
    private String vector;
}
