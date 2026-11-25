package com.opspilot.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_instance")
public class ServiceInstance {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String baseUrl;
    private String status;
    private LocalDateTime lastHeartbeat;
}
