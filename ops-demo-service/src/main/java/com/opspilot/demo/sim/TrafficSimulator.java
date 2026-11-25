package com.opspilot.demo.sim;

import com.opspilot.demo.config.AppConfig;
import com.opspilot.demo.fault.FaultManager;
import com.opspilot.demo.log.AppLogBuffer;
import com.opspilot.demo.metrics.MetricsWindow;
import com.opspilot.demo.metrics.RequestContext;
import com.opspilot.demo.service.OrderService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 流量模拟器：持续产生订单查询流量，让指标/日志在没有外部压测时也持续存在。
 * 所有请求都经过 orderQueryExecutor 线程池，线程池指标真实。
 */
@Component
public class TrafficSimulator {

    private static final int MAX_ORDER_ID = 50_000;

    private final ThreadPoolExecutor executor;
    private final AtomicLong rejectedCounter;
    private final OrderService orderService;
    private final FaultManager faultManager;
    private final MetricsWindow metricsWindow;
    private final AppLogBuffer logBuffer;
    private final Random random = new Random();

    /** 每类故障的关键日志节流：30s 一条，避免刷屏 */
    private final Map<String, Long> lastLogAt = new ConcurrentHashMap<>();

    public TrafficSimulator(ThreadPoolExecutor orderQueryExecutor, AtomicLong rejectedCounter,
                            OrderService orderService, FaultManager faultManager,
                            MetricsWindow metricsWindow, AppLogBuffer logBuffer) {
        this.executor = orderQueryExecutor;
        this.rejectedCounter = rejectedCounter;
        this.orderService = orderService;
        this.faultManager = faultManager;
        this.metricsWindow = metricsWindow;
        this.logBuffer = logBuffer;
    }

    @Scheduled(fixedDelay = 200, initialDelay = 8000)
    public void tick() {
        int batch = orderService.isRateLimited() ? 1 : 3;
        for (int i = 0; i < batch; i++) {
            boolean listQuery = random.nextInt(100) < 25;
            String traceId = logBuffer.newTraceId();
            try {
                executor.submit(() -> doRequest(listQuery, traceId));
            } catch (RuntimeException e) {
                // 线程池拒绝
                metricsWindow.recordRequest(1, true, 0, 0);
                throttledLog("THREAD_POOL", "WARN",
                        "订单查询线程池已满，任务被拒绝: active=" + executor.getActiveCount()
                                + "/" + executor.getMaximumPoolSize()
                                + " queue=" + executor.getQueue().size()
                                + " rejected=" + rejectedCounter.get(), null);
            }
        }
    }

    private void doRequest(boolean listQuery, String traceId) {
        long t0 = System.nanoTime();
        boolean error = false;
        try {
            // 线程池耗尽故障：任务睡眠占满工作线程
            int tpIntensity = faultManager.intensityOf(FaultManager.FaultType.THREAD_POOL_EXHAUSTION);
            if (tpIntensity > 0) {
                Thread.sleep(300L * tpIntensity);
                throttledLog("THREAD_POOL", "WARN",
                        "订单查询任务处理缓慢，线程池水位: active=" + executor.getActiveCount()
                                + "/" + executor.getMaximumPoolSize()
                                + " queue=" + executor.getQueue().size(), traceId);
            }

            if (listQuery) {
                int page = 1 + random.nextInt(5);
                orderService.list(page, 20, traceId);
            } else {
                // 80% 命中热点 key（~200 个），模拟真实缓存场景
                long id = random.nextInt(100) < 80
                        ? 1 + random.nextInt(200)
                        : 1 + random.nextInt(MAX_ORDER_ID);
                orderService.getById(id, traceId);
            }
            if (random.nextInt(100) < 2) {
                logBuffer.info("ORDER_QUERY", "订单查询完成 traceId=" + traceId, traceId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = true;
        } catch (Exception e) {
            error = true;
            if (!"DOWNSTREAM_TIMEOUT".equals(e.getMessage())) {
                throttledLog("ORDER_ERROR", "ERROR", "订单查询异常: " + e.getMessage(), traceId);
            }
        } finally {
            double latency = (System.nanoTime() - t0) / 1_000_000.0;
            double[] rt = RequestContext.current();
            metricsWindow.recordRequest(latency, error, rt[0], rt[1]);
            RequestContext.clear();
        }
    }

    private void throttledLog(String key, String level, String message, String traceId) {
        long now = System.currentTimeMillis();
        Long last = lastLogAt.get(key);
        if (last == null || now - last > 30_000) {
            lastLogAt.put(key, now);
            if ("ERROR".equals(level)) {
                logBuffer.error(key, message, traceId);
            } else {
                logBuffer.warn(key, message, traceId);
            }
        }
    }

    /** 缓存命中率低时的周期性告警日志 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 40_000)
    public void cacheHealthLog() {
        if (faultManager.isActive(FaultManager.FaultType.CACHE_BREAKDOWN)) {
            Map<String, Object> snap = metricsWindow.snapshot(
                    executor.getActiveCount(), executor.getQueue().size(),
                    executor.getMaximumPoolSize(), rejectedCounter.get());
            logBuffer.warn("CACHE_HIT_LOW", "缓存命中率降至 " + snap.get("cacheHitRate")
                    + "%，回源 DB QPS 放大，请检查缓存层", null);
        }
    }
}
