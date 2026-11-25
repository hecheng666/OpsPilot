package com.opspilot.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@MapperScan("com.opspilot.server.mapper")
public class PilotServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PilotServerApplication.class, args);
    }
}
