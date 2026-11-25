package com.opspilot.server.collector;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.client.DemoClient;
import com.opspilot.server.entity.ChangeEvent;
import com.opspilot.server.mapper.ChangeEventMapper;
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

/**
 * 变更采集器：每 30s 同步 demo 变更事件，按 (title, createdAt) 去重。
 */
@Component
public class ChangeCollector {

    private static final Logger log = LoggerFactory.getLogger(ChangeCollector.class);

    private final DemoClient demoClient;
    private final ChangeEventMapper changeEventMapper;

    @Value("${opspilot.service-name}")
    private String serviceName;

    public ChangeCollector(DemoClient demoClient, ChangeEventMapper changeEventMapper) {
        this.demoClient = demoClient;
        this.changeEventMapper = changeEventMapper;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void collect() {
        try {
            List<Map<String, Object>> events = demoClient.getChanges();
            if (events == null) {
                return;
            }
            for (Map<String, Object> e : events) {
                String title = String.valueOf(e.get("title"));
                long tsMs = ((Number) e.get("createdAt")).longValue();
                LocalDateTime createdAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(tsMs), ZoneId.systemDefault());

                QueryWrapper<ChangeEvent> q = new QueryWrapper<ChangeEvent>()
                        .eq("service", serviceName).eq("title", title).eq("created_at", createdAt);
                if (changeEventMapper.selectCount(q) > 0) {
                    continue;
                }
                ChangeEvent ce = new ChangeEvent();
                ce.setService(serviceName);
                ce.setType(String.valueOf(e.get("type")));
                ce.setTitle(title);
                ce.setDetail(e.get("detail") == null ? null : String.valueOf(e.get("detail")));
                ce.setOperator(e.get("operator") == null ? null : String.valueOf(e.get("operator")));
                ce.setCreatedAt(createdAt);
                changeEventMapper.insert(ce);
            }
        } catch (Exception e) {
            log.debug("变更采集失败: {}", e.getMessage());
        }
    }
}
