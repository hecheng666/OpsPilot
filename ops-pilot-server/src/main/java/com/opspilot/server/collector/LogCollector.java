package com.opspilot.server.collector;

import com.opspilot.server.client.DemoClient;
import com.opspilot.server.entity.LogEntry;
import com.opspilot.server.mapper.LogEntryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 日志采集器：每 5s 按自增 id 增量拉取 demo 日志。
 * 首次启动以当前 maxId 为基线，不回填历史。
 */
@Component
public class LogCollector {

    private static final Logger log = LoggerFactory.getLogger(LogCollector.class);

    private final DemoClient demoClient;
    private final LogEntryMapper logEntryMapper;
    private final AtomicLong lastId = new AtomicLong(-1); // -1 = 未初始化

    @Value("${opspilot.service-name}")
    private String serviceName;

    public LogCollector(DemoClient demoClient, LogEntryMapper logEntryMapper) {
        this.demoClient = demoClient;
        this.logEntryMapper = logEntryMapper;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 6000)
    public void collect() {
        try {
            if (lastId.get() < 0) {
                Map<String, Object> resp = demoClient.getLogs(0, 1);
                if (resp != null && resp.get("maxId") != null) {
                    lastId.set(((Number) resp.get("maxId")).longValue());
                    log.info("日志采集基线: sinceId={}", lastId.get());
                }
                return;
            }
            Map<String, Object> resp = demoClient.getLogs(lastId.get(), 500);
            if (resp == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) resp.get("entries");
            if (entries == null || entries.isEmpty()) {
                return;
            }
            long max = lastId.get();
            for (Map<String, Object> e : entries) {
                long id = ((Number) e.get("id")).longValue();
                LogEntry le = new LogEntry();
                le.setService(serviceName);
                le.setLevel(String.valueOf(e.get("level")));
                le.setTraceId(e.get("traceId") == null ? null : String.valueOf(e.get("traceId")));
                le.setPattern(e.get("pattern") == null ? null : String.valueOf(e.get("pattern")));
                le.setMessage(String.valueOf(e.get("message")));
                long tsMs = ((Number) e.get("ts")).longValue();
                le.setTs(LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), ZoneId.systemDefault()));
                logEntryMapper.insert(le);
                max = Math.max(max, id);
            }
            lastId.set(max);
        } catch (Exception e) {
            log.debug("日志采集失败: {}", e.getMessage());
        }
    }
}
