package com.opspilot.server.controller;

import com.opspilot.server.client.DemoClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 故障演练代理：转发到 demo-service。
 */
@RestController
@RequestMapping("/api/faults")
public class FaultController {

    private final DemoClient demoClient;

    public FaultController(DemoClient demoClient) {
        this.demoClient = demoClient;
    }

    @PostMapping("/inject")
    public Map<String, Object> inject(@RequestBody Map<String, Object> body) {
        return demoClient.injectFault(body);
    }

    @PostMapping("/recover")
    public Map<String, Object> recover(@RequestBody(required = false) Map<String, Object> body) {
        return demoClient.recoverFault(body == null ? Map.of() : body);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return demoClient.getFaultStatus();
    }
}
