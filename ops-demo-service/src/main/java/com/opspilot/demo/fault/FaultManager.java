package com.opspilot.demo.fault;

import com.opspilot.demo.log.AppLogBuffer;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障注入管理器（内存态，注入/恢复均幂等）。
 * intensity 1-10，durationSeconds 到期自动恢复。
 */
@Component
public class FaultManager {

    public enum FaultType {
        SLOW_SQL, CACHE_BREAKDOWN, THREAD_POOL_EXHAUSTION, DOWNSTREAM_TIMEOUT
    }

    @Getter
    public static class ActiveFault {
        private final String type;
        private final int intensity;
        private final long startedAtEpoch;
        private final int durationSeconds;

        public ActiveFault(String type, int intensity, int durationSeconds) {
            this.type = type;
            this.intensity = intensity;
            this.startedAtEpoch = Instant.now().getEpochSecond();
            this.durationSeconds = durationSeconds;
        }

        public boolean expired() {
            return durationSeconds > 0
                    && Instant.now().getEpochSecond() > startedAtEpoch + durationSeconds;
        }
    }

    private final Map<String, ActiveFault> active = new ConcurrentHashMap<>();
    private final AppLogBuffer logBuffer;

    public FaultManager(AppLogBuffer logBuffer) {
        this.logBuffer = logBuffer;
    }

    public synchronized Map<String, Object> inject(String type, int intensity, int durationSeconds) {
        FaultType.valueOf(type); // 校验类型合法
        intensity = Math.max(1, Math.min(10, intensity));
        if (active.containsKey(type)) {
            return Map.of("success", true, "message", "故障 " + type + " 已处于注入状态，幂等忽略", "active", statusList());
        }
        active.put(type, new ActiveFault(type, intensity, durationSeconds));
        logBuffer.warn("FAULT_INJECT", "故障注入生效: type=" + type + ", intensity=" + intensity
                + ", durationSeconds=" + durationSeconds, null);
        return Map.of("success", true, "message", "故障 " + type + " 注入成功", "active", statusList());
    }

    public synchronized Map<String, Object> recover(String type) {
        if (type == null || type.isBlank()) {
            int n = active.size();
            active.clear();
            if (n > 0) {
                logBuffer.info("FAULT_RECOVER", "全部故障已恢复，系统回到基线状态", null);
            }
            return Map.of("success", true, "message", "已恢复全部故障(" + n + "个)", "active", statusList());
        }
        ActiveFault removed = active.remove(type);
        if (removed != null) {
            logBuffer.info("FAULT_RECOVER", "故障已恢复: type=" + type, null);
        }
        return Map.of("success", true,
                "message", removed != null ? "故障 " + type + " 已恢复" : "故障 " + type + " 未在注入状态，幂等忽略",
                "active", statusList());
    }

    public boolean isActive(FaultType type) {
        return get(type) != null;
    }

    public int intensityOf(FaultType type) {
        ActiveFault f = get(type);
        return f == null ? 0 : f.getIntensity();
    }

    /** 惰性清理过期故障 */
    private ActiveFault get(FaultType type) {
        ActiveFault f = active.get(type.name());
        if (f != null && f.expired()) {
            active.remove(type.name());
            logBuffer.info("FAULT_RECOVER", "故障到期自动恢复: type=" + type.name(), null);
            return null;
        }
        return f;
    }

    public List<Map<String, Object>> statusList() {
        return active.values().stream()
                .filter(f -> !f.expired())
                .map(f -> Map.<String, Object>of(
                        "type", f.getType(),
                        "intensity", f.getIntensity(),
                        "startedAt", f.getStartedAtEpoch(),
                        "durationSeconds", f.getDurationSeconds()))
                .toList();
    }

    public Map<String, Object> status() {
        return Map.of("service", "order-demo", "faults", statusList());
    }
}
