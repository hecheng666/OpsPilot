package com.opspilot.demo.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.opspilot.demo.entity.OrderEntity;
import com.opspilot.demo.fault.FaultManager;
import com.opspilot.demo.log.AppLogBuffer;
import com.opspilot.demo.mapper.OrderMapper;
import com.opspilot.demo.metrics.MetricsWindow;
import com.opspilot.demo.metrics.RequestContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

/**
 * 订单查询业务：所有请求经过缓存层 + DB + 下游库存 mock，故障效果在此体现。
 */
@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final Cache<Long, OrderEntity> orderCache;
    private final FaultManager faultManager;
    private final MetricsWindow metricsWindow;
    private final AppLogBuffer logBuffer;
    private final Random random = new Random();

    /** 限流动作生效标志（ENABLE_RATE_LIMIT 动作模拟） */
    private volatile boolean rateLimited = false;

    public OrderService(OrderMapper orderMapper, Cache<Long, OrderEntity> orderCache,
                        FaultManager faultManager, MetricsWindow metricsWindow, AppLogBuffer logBuffer) {
        this.orderMapper = orderMapper;
        this.orderCache = orderCache;
        this.faultManager = faultManager;
        this.metricsWindow = metricsWindow;
        this.logBuffer = logBuffer;
    }

    public boolean isRateLimited() {
        return rateLimited;
    }

    public void setRateLimited(boolean rateLimited) {
        this.rateLimited = rateLimited;
    }

    public void clearCache() {
        orderCache.invalidateAll();
    }

    /** 按 ID 查询（缓存路径，CACHE_BREAKDOWN 在此生效） */
    public OrderEntity getById(long id, String traceId) {
        double dsRt = callDownstream(traceId);

        double dbRt = 0;
        OrderEntity order = null;
        boolean cacheHit = false;
        int cacheIntensity = faultManager.intensityOf(FaultManager.FaultType.CACHE_BREAKDOWN);

        if (cacheIntensity > 0) {
            // 缓存击穿：命中率从 ~95% 掉到随强度降低（10 级约 30%）
            double missProb = Math.min(0.95, 0.05 + cacheIntensity * 0.065);
            if (random.nextDouble() < missProb) {
                orderCache.invalidate(id);
            }
        }

        OrderEntity cached = orderCache.getIfPresent(id);
        if (cached != null) {
            cacheHit = true;
            order = cached;
        } else {
            long t0 = System.nanoTime();
            order = orderMapper.selectById(id);
            dbRt = (System.nanoTime() - t0) / 1_000_000.0;
            if (order != null) {
                orderCache.put(id, order);
            }
            if (cacheIntensity > 0) {
                logBuffer.warn("CACHE_MISS", "缓存未命中，回源 DB 查询订单 id=" + id
                        + "，dbRt=" + Math.round(dbRt) + "ms", traceId);
            }
        }
        metricsWindow.recordCache(cacheHit);
        RequestContext.record(dbRt, dsRt);
        return order;
    }

    /** 列表查询（慢 SQL 载体） */
    public List<OrderEntity> list(int page, int size, String traceId) {
        int slowIntensity = faultManager.intensityOf(FaultManager.FaultType.SLOW_SQL);
        long t0 = System.nanoTime();
        List<OrderEntity> result = List.of();
        String sqlDesc;
        if (slowIntensity > 0) {
            // 慢 SQL：无索引 LIKE 零命中全表扫描，按强度重复执行模拟 N+1 放大；高强度叠加深分页
            int repeat = Math.max(4, slowIntensity * 2);
            sqlDesc = "SELECT * FROM demo_order WHERE UPPER(remark) LIKE '%加急XYZ%'（无索引全表扫描，重复 " + repeat + " 次）";
            for (int i = 0; i < repeat; i++) {
                result = orderMapper.slowLikeQuery("加急XYZ");
            }
            if (slowIntensity > 5) {
                result = orderMapper.deepPageQuery(20_000L * page);
                sqlDesc += " + 深分页 LIMIT " + (20_000L * page) + ",10";
            }
        } else {
            sqlDesc = null;
            Page<OrderEntity> p = new Page<>(page, size);
            result = orderMapper.selectPage(p, null).getRecords();
        }
        double dbRt = (System.nanoTime() - t0) / 1_000_000.0;

        if (slowIntensity > 0) {
            logBuffer.warn("SLOW_QUERY", "订单列表查询耗时 " + Math.round(dbRt)
                    + "ms，超过阈值 800ms，疑似慢 SQL: " + sqlDesc, traceId);
        }
        RequestContext.record(dbRt, 0);
        return result;
    }

    /** 模拟下游库存服务：正常 ~5ms；DOWNSTREAM_TIMEOUT 时按强度 sleep，30% 概率报错 */
    private double callDownstream(String traceId) {
        int intensity = faultManager.intensityOf(FaultManager.FaultType.DOWNSTREAM_TIMEOUT);
        long t0 = System.nanoTime();
        try {
            if (intensity > 0) {
                Thread.sleep(300L * intensity);
                if (random.nextDouble() < 0.3) {
                    logBuffer.error("DOWNSTREAM_ERROR",
                            "调用库存服务 inventory-svc 超时 " + (300L * intensity)
                                    + "ms，connection read timeout，触发熔断计数", traceId);
                    throw new RuntimeException("DOWNSTREAM_TIMEOUT");
                }
                logBuffer.warn("DOWNSTREAM_SLOW",
                        "调用库存服务 inventory-svc 耗时 " + (300L * intensity) + "ms，接近超时阈值", traceId);
            } else {
                Thread.sleep(2 + random.nextInt(6));
            }
            return (System.nanoTime() - t0) / 1_000_000.0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
