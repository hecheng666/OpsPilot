package com.opspilot.demo.controller;

import com.opspilot.demo.change.ChangeStore;
import com.opspilot.demo.fault.FaultManager;
import com.opspilot.demo.log.AppLogBuffer;
import com.opspilot.demo.metrics.MetricsWindow;
import com.opspilot.demo.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内部接口：仅本机/平台调用，承载故障注入、指标、日志、变更与处置动作。
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final FaultManager faultManager;
    private final MetricsWindow metricsWindow;
    private final AppLogBuffer logBuffer;
    private final ChangeStore changeStore;
    private final OrderService orderService;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCounter;

    public InternalController(FaultManager faultManager, MetricsWindow metricsWindow,
                              AppLogBuffer logBuffer, ChangeStore changeStore,
                              OrderService orderService, ThreadPoolExecutor orderQueryExecutor,
                              AtomicLong rejectedCounter) {
        this.faultManager = faultManager;
        this.metricsWindow = metricsWindow;
        this.logBuffer = logBuffer;
        this.changeStore = changeStore;
        this.orderService = orderService;
        this.executor = orderQueryExecutor;
        this.rejectedCounter = rejectedCounter;
    }

    @PostMapping("/faults/inject")
    public Map<String, Object> inject(@RequestBody Map<String, Object> body) {
        String type = String.valueOf(body.get("type"));
        int intensity = body.get("intensity") == null ? 8 : ((Number) body.get("intensity")).intValue();
        int duration = body.get("durationSeconds") == null ? 0 : ((Number) body.get("durationSeconds")).intValue();
        Map<String, Object> result = faultManager.inject(type, intensity, duration);
        changeStore.addNow("FAULT", "注入故障 " + type, "intensity=" + intensity + ", durationSeconds=" + duration, "chaos-console");
        return result;
    }

    @PostMapping("/faults/recover")
    public Map<String, Object> recover(@RequestBody(required = false) Map<String, Object> body) {
        String type = body == null ? null : (String) body.get("type");
        Map<String, Object> result = faultManager.recover(type);
        changeStore.addNow("FAULT", type == null ? "恢复全部故障" : "恢复故障 " + type, null, "chaos-console");
        return result;
    }

    @GetMapping("/faults/status")
    public Map<String, Object> faultStatus() {
        return faultManager.status();
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metricsWindow.snapshot(
                executor.getActiveCount(), executor.getQueue().size(),
                executor.getMaximumPoolSize(), rejectedCounter.get());
    }

    @GetMapping("/logs")
    public Object logs(@RequestParam(defaultValue = "0") long sinceId,
                       @RequestParam(defaultValue = "500") int limit) {
        return Map.of("maxId", logBuffer.maxId(), "entries", logBuffer.since(sinceId, Math.min(limit, 500)));
    }

    @GetMapping("/changes")
    public Object changes() {
        return changeStore.all();
    }

    /**
     * 处置动作（模拟生效）：RECOVER_FAULT / ENABLE_RATE_LIMIT / CLEAR_CACHE / ROLLBACK_CONFIG / SCALE_THREAD_POOL
     */
    @PostMapping("/actions/execute")
    public Map<String, Object> execute(@RequestBody Map<String, Object> body) {
        String actionType = String.valueOf(body.get("actionType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());

        String message;
        boolean success = true;
        switch (actionType) {
            case "RECOVER_FAULT" -> {
                String type = (String) params.get("type");
                faultManager.recover(type);
                message = type == null ? "已恢复全部故障" : "故障 " + type + " 已恢复";
            }
            case "ENABLE_RATE_LIMIT" -> {
                orderService.setRateLimited(true);
                message = "限流规则已生效：入口 QPS 降至 5";
            }
            case "CLEAR_CACHE" -> {
                orderService.clearCache();
                message = "缓存已全部清理，回源重建中";
            }
            case "ROLLBACK_CONFIG" -> {
                orderService.setRateLimited(false);
                message = "配置已回滚至上一版本";
            }
            case "SCALE_THREAD_POOL" -> {
                int core = params.get("coreSize") == null ? 16 : ((Number) params.get("coreSize")).intValue();
                int max = params.get("maxSize") == null ? 32 : ((Number) params.get("maxSize")).intValue();
                executor.setCorePoolSize(core);
                executor.setMaximumPoolSize(max);
                message = "线程池已扩容: core=" + core + ", max=" + max;
            }
            default -> {
                success = false;
                message = "未知动作类型: " + actionType;
            }
        }
        if (success) {
            logBuffer.info("ACTION_EXECUTE", "处置动作执行成功: " + actionType + " -> " + message, null);
            changeStore.addNow("ACTION", "执行处置 " + actionType, message, "ops-pilot");
        }
        return Map.of("success", success, "message", message);
    }
}
