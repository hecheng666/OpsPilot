package com.opspilot.demo.change;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 变更事件：预置一批历史事件，故障注入/处置动作会追加运行时事件。
 */
@Component
public class ChangeStore {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @Getter
    public static class ChangeEvent {
        private final long id;
        private final String service;
        private final String type;   // DEPLOY / CONFIG / SQL / FAULT / ACTION
        private final String title;
        private final String detail;
        private final String operator;
        private final long createdAt;

        ChangeEvent(long id, String type, String title, String detail, String operator, long createdAt) {
            this.id = id;
            this.service = "order-demo";
            this.type = type;
            this.title = title;
            this.detail = detail;
            this.operator = operator;
            this.createdAt = createdAt;
        }
    }

    private final AtomicLong seq = new AtomicLong(0);
    private final CopyOnWriteArrayList<ChangeEvent> events = new CopyOnWriteArrayList<>();

    public ChangeStore() {
        long now = Instant.now().toEpochMilli();
        add("DEPLOY", "发布 order-service v2.3.1", "灰度 20% 流量，新增订单导出接口", "zhangsan", now - 2 * 3600_000L);
        add("CONFIG", "调整 Tomcat 最大连接数 200 -> 300", "配合大促压测", "lisi", now - 5 * 3600_000L);
        add("SQL", "demo_order 表清理历史数据 10 万行", "DBA 工单 DBA-20261120-007", "dba-wang", now - 26 * 3600_000L);
        add("DEPLOY", "发布 order-service v2.3.0", "例行迭代，缓存策略调整", "zhangsan", now - 50 * 3600_000L);
    }

    public void add(String type, String title, String detail, String operator, long ts) {
        events.add(new ChangeEvent(seq.incrementAndGet(), type, title, detail, operator, ts));
    }

    public void addNow(String type, String title, String detail, String operator) {
        add(type, title, detail, operator, Instant.now().toEpochMilli());
    }

    public List<ChangeEvent> all() {
        return events.stream().sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt())).toList();
    }

    public static String fmt(long ts) {
        return FMT.format(Instant.ofEpochMilli(ts));
    }
}
