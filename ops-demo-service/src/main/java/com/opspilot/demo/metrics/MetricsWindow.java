package com.opspilot.demo.metrics;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 滑动窗口指标采样器：保存最近 WINDOW_MS 的请求样本与缓存事件，聚合出当前窗口快照。
 */
@Component
public class MetricsWindow {

    private static final long WINDOW_MS = 30_000;

    public record RequestSample(long ts, double latencyMs, boolean error, double dbRtMs, double downstreamRtMs) {}
    public record CacheEvent(long ts, boolean hit) {}

    private final Deque<RequestSample> requests = new ArrayDeque<>();
    private final Deque<CacheEvent> cacheEvents = new ArrayDeque<>();

    public synchronized void recordRequest(double latencyMs, boolean error, double dbRtMs, double downstreamRtMs) {
        long now = Instant.now().toEpochMilli();
        requests.addLast(new RequestSample(now, latencyMs, error, dbRtMs, downstreamRtMs));
        evict(now);
    }

    public synchronized void recordCache(boolean hit) {
        cacheEvents.addLast(new CacheEvent(Instant.now().toEpochMilli(), hit));
        evict(Instant.now().toEpochMilli());
    }

    private void evict(long now) {
        while (!requests.isEmpty() && now - requests.peekFirst().ts() > WINDOW_MS) {
            requests.pollFirst();
        }
        while (!cacheEvents.isEmpty() && now - cacheEvents.peekFirst().ts() > WINDOW_MS) {
            cacheEvents.pollFirst();
        }
    }

    private double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    /** 当前窗口指标快照 */
    public synchronized Map<String, Object> snapshot(int threadActive, int threadQueue, int threadMax, long threadRejected) {
        long now = Instant.now().toEpochMilli();
        evict(now);
        List<RequestSample> snapshot = new ArrayList<>(requests);
        double windowSec = Math.max(1.0, WINDOW_MS / 1000.0);

        List<Double> latencies = snapshot.stream().map(RequestSample::latencyMs).sorted().toList();
        List<Double> dbRts = snapshot.stream().map(RequestSample::dbRtMs).sorted(Comparator.reverseOrder()).toList();
        List<Double> dsRts = snapshot.stream().map(RequestSample::downstreamRtMs).sorted(Comparator.reverseOrder()).toList();
        long errors = snapshot.stream().filter(RequestSample::error).count();

        long hits = cacheEvents.stream().filter(CacheEvent::hit).count();
        long totalCache = cacheEvents.size();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", now);
        m.put("qps", round(snapshot.size() / windowSec));
        m.put("p50", round(percentile(latencies, 50)));
        m.put("p95", round(percentile(latencies, 95)));
        m.put("p99", round(percentile(latencies, 99)));
        m.put("errorRate", round(snapshot.isEmpty() ? 0 : errors * 100.0 / snapshot.size()));
        m.put("dbRtMs", round(dbRts.isEmpty() ? 0 : dbRts.stream().mapToDouble(d -> d).average().orElse(0)));
        m.put("cacheHitRate", round(totalCache == 0 ? 100 : hits * 100.0 / totalCache));
        m.put("downstreamRtMs", round(dsRts.isEmpty() ? 0 : dsRts.stream().mapToDouble(d -> d).average().orElse(0)));
        m.put("threadActive", threadActive);
        m.put("threadQueue", threadQueue);
        m.put("threadMax", threadMax);
        m.put("threadRejected", threadRejected);
        return m;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
