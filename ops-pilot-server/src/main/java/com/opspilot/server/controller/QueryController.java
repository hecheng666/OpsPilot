package com.opspilot.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.entity.LogEntry;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.mapper.LogEntryMapper;
import com.opspilot.server.mapper.MetricPointMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 指标/日志查询接口。
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final MetricPointMapper metricPointMapper;
    private final LogEntryMapper logEntryMapper;

    @Value("${opspilot.service-name}")
    private String serviceName;

    public QueryController(MetricPointMapper metricPointMapper, LogEntryMapper logEntryMapper) {
        this.metricPointMapper = metricPointMapper;
        this.logEntryMapper = logEntryMapper;
    }

    @GetMapping("/metrics/query")
    public List<Map<String, Object>> metricQuery(@RequestParam String metric,
                                                 @RequestParam(defaultValue = "30") int windowMinutes) {
        windowMinutes = Math.min(windowMinutes, 60);
        LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
        List<MetricPoint> points = metricPointMapper.selectList(new QueryWrapper<MetricPoint>()
                .eq("service", serviceName).eq("metric", metric)
                .ge("ts", since).orderByAsc("ts").last("LIMIT 2000"));
        List<Map<String, Object>> arr = new ArrayList<>();
        for (MetricPoint p : points) {
            arr.add(Map.of("ts", p.getTs().toString(), "value", p.getValue()));
        }
        return arr;
    }

    @GetMapping("/logs/search")
    public List<LogEntry> logSearch(@RequestParam(required = false) String level,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "30") int windowMinutes,
                                    @RequestParam(defaultValue = "100") int limit) {
        limit = Math.min(limit, 200);
        LocalDateTime since = LocalDateTime.now().minusMinutes(Math.min(windowMinutes, 60));
        QueryWrapper<LogEntry> q = new QueryWrapper<LogEntry>()
                .eq("service", serviceName).ge("ts", since)
                .orderByDesc("ts").last("LIMIT " + limit);
        if (level != null && !level.isBlank()) {
            q.eq("level", level);
        }
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like("message", keyword).or().like("pattern", keyword));
        }
        return logEntryMapper.selectList(q);
    }
}
