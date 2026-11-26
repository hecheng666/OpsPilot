package com.opspilot.server.alert;

import com.opspilot.server.collector.MetricCollector;
import com.opspilot.server.entity.Incident;
import com.opspilot.server.entity.MetricPoint;
import com.opspilot.server.mapper.IncidentMapper;
import com.opspilot.server.mapper.MetricPointMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 告警自动恢复：阈值对称性与连续窗口计数。
 */
class AlertRecoveryTest {

    private MetricPointMapper metricPointMapper;
    private IncidentMapper incidentMapper;
    private AlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        metricPointMapper = mock(MetricPointMapper.class);
        incidentMapper = mock(IncidentMapper.class);
        MetricCollector metricCollector = mock(MetricCollector.class);
        when(metricCollector.isServiceDown()).thenReturn(false);
        evaluator = new AlertEvaluator(metricPointMapper, incidentMapper, metricCollector);
        ReflectionTestUtils.setField(evaluator, "serviceName", "order-demo");
    }

    @Test
    @DisplayName("缓存命中率恢复判定与告警阈值对称：59.9 不健康，60 健康")
    void cacheHitRecoveryBoundary() {
        String title = "缓存命中率低于 60%（当前 49.09%）";
        assertThat(AlertEvaluator.isHealthy(title, false, 0, 0, 0, 0, 59.9, 0)).isFalse();
        assertThat(AlertEvaluator.isHealthy(title, false, 0, 0, 0, 0, 60.0, 0)).isTrue();
    }

    @Test
    @DisplayName("P95 与 DB RT 恢复判定与告警阈值对称")
    void latencyRecoveryBoundary() {
        String p95Title = "P95 延迟超过阈值 800ms（当前 1963ms）";
        assertThat(AlertEvaluator.isHealthy(p95Title, false, 800, 0, 0, 0, 0, 0)).isTrue();
        assertThat(AlertEvaluator.isHealthy(p95Title, false, 800.1, 0, 0, 0, 0, 0)).isFalse();

        String dbTitle = "DB 平均 RT 超过 500ms（当前 505ms）";
        assertThat(AlertEvaluator.isHealthy(dbTitle, false, 0, 0, 0, 0, 0, 500)).isTrue();
        assertThat(AlertEvaluator.isHealthy(dbTitle, false, 0, 0, 0, 0, 0, 501)).isFalse();
    }

    @Test
    @DisplayName("线程池使用率恢复判定：75% 健康、87.5% 不健康、无数据不健康")
    void threadPoolRecoveryBoundary() {
        String title = "订单线程池使用率超过 80%（active=16/16）";
        assertThat(AlertEvaluator.isHealthy(title, false, 0, 0, 12, 16, 0, 0)).isTrue();
        assertThat(AlertEvaluator.isHealthy(title, false, 0, 0, 14, 16, 0, 0)).isFalse();
        assertThat(AlertEvaluator.isHealthy(title, false, 0, 0, 0, 0, 0, 0)).isFalse();
    }

    @Test
    @DisplayName("服务不可用事故在采集恢复后判定为健康")
    void serviceDownRecovery() {
        String title = "服务不可用：连续 3 次采集失败";
        assertThat(AlertEvaluator.isHealthy(title, false, 0, 0, 0, 0, 0, 0)).isTrue();
        assertThat(AlertEvaluator.isHealthy(title, true, 0, 0, 0, 0, 0, 0)).isFalse();
    }

    @Test
    @DisplayName("指标连续 3 个窗口正常后才自动关闭事故")
    void autoResolvesAfterThreeHealthyWindows() {
        Incident incident = openCacheIncident();
        when(incidentMapper.selectList(any())).thenReturn(List.of(incident));
        when(metricPointMapper.selectList(any())).thenReturn(List.of(cachePoint(80.0)));

        evaluator.evaluate();
        evaluator.evaluate();
        verify(incidentMapper, never()).updateById(any(Incident.class));

        evaluator.evaluate();
        verify(incidentMapper, times(1)).updateById(any(Incident.class));
        assertThat(incident.getStatus()).isEqualTo("CLOSED");
        assertThat(incident.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("指标再次异常会重置连续正常计数，需重新累计满 3 个窗口")
    void abnormalMetricResetsCounter() {
        Incident incident = openCacheIncident();
        when(incidentMapper.selectList(any())).thenReturn(List.of(incident));

        when(metricPointMapper.selectList(any())).thenReturn(List.of(cachePoint(80.0)));
        evaluator.evaluate();
        evaluator.evaluate();

        when(metricPointMapper.selectList(any())).thenReturn(List.of(cachePoint(40.0)));
        evaluator.evaluate();
        verify(incidentMapper, never()).updateById(any(Incident.class));

        when(metricPointMapper.selectList(any())).thenReturn(List.of(cachePoint(80.0)));
        evaluator.evaluate();
        evaluator.evaluate();
        verify(incidentMapper, never()).updateById(any(Incident.class));

        evaluator.evaluate();
        verify(incidentMapper, times(1)).updateById(any(Incident.class));
    }

    private Incident openCacheIncident() {
        Incident incident = new Incident();
        incident.setId(1L);
        incident.setService("order-demo");
        incident.setTitle("缓存命中率低于 60%（当前 49.09%）");
        incident.setSeverity("P2");
        incident.setStatus("OPEN");
        incident.setStartedAt(LocalDateTime.now());
        incident.setCreatedAt(LocalDateTime.now());
        return incident;
    }

    private MetricPoint cachePoint(double value) {
        MetricPoint point = new MetricPoint();
        point.setService("order-demo");
        point.setMetric("cacheHitRate");
        point.setValue(value);
        point.setTs(LocalDateTime.now());
        return point;
    }
}
