package com.opspilot.demo.controller;

import com.opspilot.demo.entity.OrderEntity;
import com.opspilot.demo.log.AppLogBuffer;
import com.opspilot.demo.metrics.MetricsWindow;
import com.opspilot.demo.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单业务接口（与流量模拟器共用同一执行路径）。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCounter;
    private final MetricsWindow metricsWindow;
    private final AppLogBuffer logBuffer;

    public OrderController(OrderService orderService, ThreadPoolExecutor orderQueryExecutor,
                           AtomicLong rejectedCounter, MetricsWindow metricsWindow, AppLogBuffer logBuffer) {
        this.orderService = orderService;
        this.executor = orderQueryExecutor;
        this.rejectedCounter = rejectedCounter;
        this.metricsWindow = metricsWindow;
        this.logBuffer = logBuffer;
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable long id) throws Exception {
        String traceId = logBuffer.newTraceId();
        long t0 = System.nanoTime();
        try {
            OrderEntity order = executor.submit(() -> orderService.getById(id, traceId)).get();
            metricsWindow.recordRequest((System.nanoTime() - t0) / 1e6, order == null, 0, 0);
            if (order == null) {
                return Map.of("code", 404, "message", "订单不存在");
            }
            return order;
        } catch (RuntimeException e) {
            rejectedCounter.incrementAndGet();
            metricsWindow.recordRequest((System.nanoTime() - t0) / 1e6, true, 0, 0);
            return Map.of("code", 503, "message", "系统繁忙，请稍后重试");
        }
    }

    @GetMapping
    public Object list(@RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "20") int size) throws Exception {
        String traceId = logBuffer.newTraceId();
        long t0 = System.nanoTime();
        try {
            List<OrderEntity> orders = executor.submit(() -> orderService.list(page, size, traceId)).get();
            metricsWindow.recordRequest((System.nanoTime() - t0) / 1e6, false, 0, 0);
            return Map.of("code", 0, "page", page, "size", size, "data", orders);
        } catch (RuntimeException e) {
            rejectedCounter.incrementAndGet();
            metricsWindow.recordRequest((System.nanoTime() - t0) / 1e6, true, 0, 0);
            return Map.of("code", 503, "message", "系统繁忙，请稍后重试");
        }
    }
}
