package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("change_event")
public class ChangeEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String service;
    private String type;
    private String title;
    private String detail;
    private String operator;
    private LocalDateTime createdAt;
}
