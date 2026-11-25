package com.opspilot.server.collector;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.client.DemoClient;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.entity.ServiceInstance;
import com.opspilot.server.mapper.MetricPointMapper;
import com.opspilot.server.mapper.ServiceInstanceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指标采集器：每 5s 拉取 demo /internal/metrics，每个指标一行落 metric_point。
 * 连续 3 次失败将服务标记为 DOWN。
 */
@Component
public class MetricCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricCollector.class);

    /** 只采集这些数值型指标 */
    private static final List<String> METRICS = List.of(
            "qps", "p50", "p95", "p99", "errorRate", "dbRtMs",
            "cacheHitRate", "downstreamRtMs", "threadActive", "threadQueue", "threadMax", "threadRejected");

    private final DemoClient demoClient;
    private final MetricPointMapper metricPointMapper;
    private final ServiceInstanceMapper serviceInstanceMapper;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    @Value("${opspilot.service-name}")
    private String serviceName;

    public MetricCollector(DemoClient demoClient, MetricPointMapper metricPointMapper,
                           ServiceInstanceMapper serviceInstanceMapper) {
        this.demoClient = demoClient;
        this.metricPointMapper = metricPointMapper;
        this.serviceInstanceMapper = serviceInstanceMapper;
    }

    @Bean
    public ApplicationRunner registerService() {
        return args -> {
            QueryWrapper<ServiceInstance> q = new QueryWrapper<ServiceInstance>().eq("name", serviceName);
            if (serviceInstanceMapper.selectCount(q) == 0) {
                ServiceInstance s = new ServiceInstance();
                s.setName(serviceName);
                s.setBaseUrl(demoClient.getBaseUrl());
                s.setStatus("DOWN");
                serviceInstanceMapper.insert(s);
                log.info("注册服务实例: {}", serviceName);
            }
        };
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void collect() {
        try {
            Map<String, Object> snapshot = demoClient.getMetrics();
            consecutiveFailures.set(0);
            updateServiceStatus("UP");

            long tsMs = snapshot.get("ts") == null
                    ? Instant.now().toEpochMilli()
                    : ((Number) snapshot.get("ts")).longValue();
            LocalDateTime ts = LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), ZoneId.systemDefault());

            List<MetricPoint> points = new ArrayList<>();
            for (String key : METRICS) {
                Object v = snapshot.get(key);
                if (v instanceof Number num) {
                    MetricPoint p = new MetricPoint();
                    p.setService(serviceName);
                    p.setMetric(key);
                    p.setValue(((Number) v).doubleValue());
                    p.setTs(ts);
                    points.add(p);
                }
            }
            points.forEach(metricPointMapper::insert);
        } catch (Exception e) {
            int fails = consecutiveFailures.incrementAndGet();
            log.warn("指标采集失败({}/3): {}", fails, e.getMessage());
            if (fails >= 3) {
                updateServiceStatus("DOWN");
            }
        }
    }

    private void updateServiceStatus(String status) {
        QueryWrapper<ServiceInstance> q = new QueryWrapper<ServiceInstance>().eq("name", serviceName);
        ServiceInstance s = serviceInstanceMapper.selectOne(q);
        if (s != null) {
            s.setStatus(status);
            s.setLastHeartbeat(LocalDateTime.now());
            serviceInstanceMapper.updateById(s);
        }
    }

    public boolean isServiceDown() {
        return consecutiveFailures.get() >= 3;
    }
}
