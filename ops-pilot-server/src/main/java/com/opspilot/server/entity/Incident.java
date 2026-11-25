package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("incident")
public class Incident {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String service;
    private String title;
    private String severity;
    private String status;
    private String rootCause;
    private String report;
    private LocalDateTime startedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
}
