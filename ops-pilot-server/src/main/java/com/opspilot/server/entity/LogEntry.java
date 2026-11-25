package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("log_entry")
public class LogEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String service;
    private String level;
    private String traceId;
    private String pattern;
    private String message;
    private LocalDateTime ts;
}
