package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("metric_point")
public class MetricPoint {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String service;
    private String metric;
    private Double value;
    private LocalDateTime ts;
}
