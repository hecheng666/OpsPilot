package com.opspilot.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.client.DemoClient;
import com.opspilot.server.entity.Incident;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.entity.ServiceInstance;
import com.opspilot.server.mapper.IncidentMapper;
import com.opspilot.server.mapper.MetricPointMapper;
import com.opspilot.server.mapper.ServiceInstanceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 总览页聚合接口：KPI 卡片 + 时序 + 活跃事故 + 服务状态 + 当前故障。
 */
@RestController
@RequestMapping("/api")
public class OverviewController {

    private static final List<String> LATEST_METRICS = List.of(
            "qps", "p95", "errorRate", "cacheHitRate", "dbRtMs", "threadActive", "threadQueue", "threadMax");
    private static final List<String> SERIES_METRICS = List.of("qps", "p95", "errorRate", "cacheHitRate");

    private final MetricPointMapper metricPointMapper;
    private final IncidentMapper incidentMapper;
    private final ServiceInstanceMapper serviceInstanceMapper;
    private final DemoClient demoClient;

    @Value("${opspilot.service-name}")
    private String serviceName;

    public OverviewController(MetricPointMapper metricPointMapper, IncidentMapper incidentMapper,
                              ServiceInstanceMapper serviceInstanceMapper, DemoClient demoClient) {
        this.metricPointMapper = metricPointMapper;
        this.incidentMapper = incidentMapper;
        this.serviceInstanceMapper = serviceInstanceMapper;
        this.demoClient = demoClient;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 服务状态
        ServiceInstance service = serviceInstanceMapper.selectOne(
                new QueryWrapper<ServiceInstance>().eq("name", serviceName));
        result.put("service", service);

        // 最新 KPI（每指标取最新一条）
        Map<String, Object> latest = new HashMap<>();
        for (String metric : LATEST_METRICS) {
            MetricPoint p = metricPointMapper.selectOne(new QueryWrapper<MetricPoint>()
                    .eq("service", serviceName).eq("metric", metric)
                    .orderByDesc("ts").last("LIMIT 1"));
            latest.put(metric, p == null ? 0 : p.getValue());
        }
        result.put("latest", latest);

        // 最近 30 分钟时序
        LocalDateTime since = LocalDateTime.now().minusMinutes(30);
        Map<String, Object> series = new LinkedHashMap<>();
        for (String metric : SERIES_METRICS) {
            List<MetricPoint> points = metricPointMapper.selectList(new QueryWrapper<MetricPoint>()
                    .eq("service", serviceName).eq("metric", metric)
                    .ge("ts", since).orderByAsc("ts"));
            List<Map<String, Object>> arr = new ArrayList<>();
            for (MetricPoint p : points) {
                arr.add(Map.of("ts", p.getTs().toString(), "value", p.getValue()));
            }
            series.put(metric, arr);
        }
        result.put("series", series);

        // 活跃事故
        result.put("incidents", incidentMapper.selectList(new QueryWrapper<Incident>()
                .eq("service", serviceName).eq("status", "OPEN").orderByDesc("created_at").last("LIMIT 10")));

        // 当前故障（demo 直连失败时降级）
        try {
            result.put("faults", demoClient.getFaultStatus().get("faults"));
        } catch (Exception e) {
            result.put("faults", List.of());
        }
        return result;
    }
}
