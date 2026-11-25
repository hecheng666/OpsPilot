package com.opspilot.demo.log;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 结构化日志环形缓冲（5000 条），模拟 ELK 的日志源。
 */
@Component
public class AppLogBuffer {

    private static final int CAPACITY = 5000;

    @Getter
    public static class Entry {
        private final long id;
        private final String level;
        private final String traceId;
        private final String pattern;
        private final String message;
        private final long ts;

        Entry(long id, String level, String traceId, String pattern, String message) {
            this.id = id;
            this.level = level;
            this.traceId = traceId;
            this.pattern = pattern;
            this.message = message;
            this.ts = Instant.now().toEpochMilli();
        }
    }

    private final AtomicLong seq = new AtomicLong(0);
    private final Deque<Entry> buffer = new ArrayDeque<>(CAPACITY);

    public String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void add(String level, String pattern, String message, String traceId) {
        Entry e = new Entry(seq.incrementAndGet(), level, traceId, pattern, message);
        synchronized (buffer) {
            if (buffer.size() >= CAPACITY) {
                buffer.pollFirst();
            }
            buffer.addLast(e);
        }
    }

    public void info(String pattern, String message, String traceId) {
        add("INFO", pattern, message, traceId);
    }

    public void warn(String pattern, String message, String traceId) {
        add("WARN", pattern, message, traceId);
    }

    public void error(String pattern, String message, String traceId) {
        add("ERROR", pattern, message, traceId);
    }

    /** 增量拉取：返回 id > sinceId 的日志（最多 500 条） */
    public List<Entry> since(long sinceId, int limit) {
        List<Entry> out = new ArrayList<>();
        synchronized (buffer) {
            for (Entry e : buffer) {
                if (e.getId() > sinceId) {
                    out.add(e);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    public long maxId() {
        return seq.get();
    }
}
