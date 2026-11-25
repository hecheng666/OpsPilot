package com.opspilot.server.eval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opspilot.server.entity.LogEntry;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.mapper.LogEntryMapper;
import com.opspilot.server.mapper.MetricPointMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SymptomAnalyzerTest {

    @Mock
    private MetricPointMapper metricPointMapper;
    @Mock
    private LogEntryMapper logEntryMapper;

    private SymptomAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SymptomAnalyzer(metricPointMapper, logEntryMapper);
        ReflectionTestUtils.setField(analyzer, "serviceName", "order-demo");
        when(logEntryMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("DB RT 峰值超过 500ms 判为慢 SQL，建议恢复故障")
    void detectsSlowSql() {
        metrics(Map.of("dbRtMs", 600.0));

        SymptomAnalyzer.Snapshot snapshot = analyzer.analyze(5);

        assertThat(snapshot.triggers()).contains("SLOW_SQL");
        assertThat(snapshot.suggestedAction()).isEqualTo("RECOVER_FAULT");
        assertThat(snapshot.causeTitle()).contains("慢 SQL");
    }

    @Test
    @DisplayName("缓存命中率过低优先于慢 SQL")
    void cacheAbnormalTakesPriorityOverSlowSql() {
        metrics(Map.of("dbRtMs", 600.0, "cacheHitRate", 40.0));

        SymptomAnalyzer.Snapshot snapshot = analyzer.analyze(5);

        assertThat(snapshot.triggers()).contains("CACHE_HIT_LOW", "SLOW_SQL");
        assertThat(snapshot.suggestedAction()).isEqualTo("CLEAR_CACHE");
    }

    @Test
    @DisplayName("线程池使用率超过 80% 优先级最高")
    void threadSaturationHasHighestPriority() {
        metrics(Map.of("cacheHitRate", 40.0, "threadActive", 9.0, "threadMax", 10.0));

        SymptomAnalyzer.Snapshot snapshot = analyzer.analyze(5);

        assertThat(snapshot.triggers()).contains("THREAD_POOL_SATURATED");
        assertThat(snapshot.suggestedAction()).isEqualTo("SCALE_THREAD_POOL");
    }

    @Test
    @DisplayName("下游 RT 超过 1000ms 判为下游慢，建议限流")
    void detectsDownstreamSlow() {
        metrics(Map.of("downstreamRtMs", 1500.0));

        SymptomAnalyzer.Snapshot snapshot = analyzer.analyze(5);

        assertThat(snapshot.triggers()).contains("DOWNSTREAM_SLOW");
        assertThat(snapshot.suggestedAction()).isEqualTo("ENABLE_RATE_LIMIT");
    }

    @Test
    @DisplayName("仅凭 3 条 SLOW_QUERY 日志也能判出慢 SQL")
    void detectsSlowSqlFromLogsOnly() {
        when(logEntryMapper.selectList(any()))
                .thenReturn(logs("SLOW_QUERY", 3));
        metrics(Map.of());

        SymptomAnalyzer.Snapshot snapshot = analyzer.analyze(5);

        assertThat(snapshot.triggers()).contains("SLOW_QUERY_LOG", "SLOW_SQL");
        assertThat(snapshot.logPatterns()).containsExactly("SLOW_QUERY×3");
        assertThat(snapshot.suggestedAction()).isEqualTo("RECOVER_FAULT");
    }

    @Test
    @DisplayName("无指标无日志时不误报")
    void reportsNoAnomalyWhenDataClean() {
        metrics(Map.of());

        SymptomAnalyzer.Snapshot snapshot = analyzer.analyze(5);

        assertThat(snapshot.triggers()).isEmpty();
        assertThat(snapshot.suggestedAction()).isNull();
        assertThat(snapshot.causeTitle()).contains("未发现显著异常");
    }

    /** 按指标名返回对应的单点数据，模拟 SQL 的 metric 过滤 */
    private void metrics(Map<String, Double> values) {
        when(metricPointMapper.selectList(any())).thenAnswer(invocation -> {
            QueryWrapper<MetricPoint> wrapper = invocation.getArgument(0);
            // 触发 SQL 片段生成，参数值才会填充到 paramNameValuePairs
            wrapper.getSqlSegment();
            String metric = wrapper.getParamNameValuePairs().values().stream()
                    .filter(value -> value instanceof String name && values.containsKey(name))
                    .map(String.class::cast)
                    .findFirst()
                    .orElse(null);
            if (metric == null) {
                return List.of();
            }
            MetricPoint point = new MetricPoint();
            point.setService("order-demo");
            point.setMetric(metric);
            point.setValue(values.get(metric));
            point.setTs(LocalDateTime.now());
            return List.of(point);
        });
    }

    private static List<LogEntry> logs(String pattern, int count) {
        List<LogEntry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LogEntry entry = new LogEntry();
            entry.setService("order-demo");
            entry.setPattern(pattern);
            entry.setMessage("slow query detected");
            entry.setTs(LocalDateTime.now());
            entries.add(entry);
        }
        return entries;
    }
}
