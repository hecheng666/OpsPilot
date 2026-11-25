package com.opspilot.demo.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.opspilot.demo.entity.OrderEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class AppConfig {

    public static final int POOL_CORE = 8;
    public static final int POOL_MAX = 16;
    public static final int POOL_QUEUE = 50;

    /** 订单查询专用线程池：拒绝次数可统计 */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor orderQueryExecutor(AtomicLong rejectedCounter) {
        return new ThreadPoolExecutor(
                POOL_CORE, POOL_MAX,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(POOL_QUEUE),
                r -> {
                    Thread t = new Thread(r);
                    t.setName("order-query-" + t.getId());
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> {
                    rejectedCounter.incrementAndGet();
                    throw new RuntimeException("ORDER_POOL_REJECTED");
                });
    }

    @Bean
    public AtomicLong rejectedCounter() {
        return new AtomicLong(0);
    }

    /** 模拟 Redis 的本地缓存层 */
    @Bean
    public Cache<Long, OrderEntity> orderCache() {
        return Caffeine.newBuilder().maximumSize(10_000).build();
    }
}
