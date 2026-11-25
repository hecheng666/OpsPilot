package com.opspilot.server.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalScoringTest {

    private final EvalService service =
            new EvalService(null, null, null, null, null, null, null, null, null, null);

    @Test
    @DisplayName("根因命中关键词记 Top-1，忽略大小写与空格差异")
    void ranksTop1() {
        assertThat(service.rank(List.of("慢SQL"), "慢 SQL 导致数据库响应变慢", List.of())).isEqualTo(1);
        assertThat(service.rank(List.of("SLOW SQL"), "slow sql caused latency", List.of())).isEqualTo(1);
    }

    @Test
    @DisplayName("根因未命中时按备选顺序记 Top-2 / Top-3，最多看两条备选")
    void ranksAlternatives() {
        List<String> keywords = List.of("慢SQL");

        assertThat(service.rank(keywords, "缓存击穿", List.of("慢 SQL 索引失效"))).isEqualTo(2);
        assertThat(service.rank(keywords, "缓存击穿", List.of("线程池打满", "慢 SQL 索引失效"))).isEqualTo(3);
        assertThat(service.rank(keywords, "缓存击穿", List.of("线程池打满", "下游超时", "慢 SQL")))
                .isZero();
    }

    @Test
    @DisplayName("空关键词或空根因记未命中")
    void ranksZeroForMissingInput() {
        assertThat(service.rank(List.of(), "慢 SQL", List.of())).isZero();
        assertThat(service.rank(List.of("慢SQL"), null, List.of())).isZero();
        assertThat(service.rank(List.of("慢SQL"), "缓存击穿", List.of())).isZero();
    }

    @Test
    @DisplayName("报告完整率按 6 个字段计分")
    void scoresReportCompleteness() {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("summary", "订单列表变慢");
        full.put("root_cause", Map.of("title", "慢 SQL", "confidence", 0.8));
        full.put("impact", "订单查询");
        full.put("alternatives", List.of());
        full.put("prevention", List.of("补充慢查询告警"));

        assertThat(service.completeness(full)).isEqualTo(1.0);
        assertThat(service.completeness(Map.of())).isZero();

        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("summary", "有摘要");
        partial.put("impact", "有影响面");
        partial.put("alternatives", List.of());
        assertThat(service.completeness(partial)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("关键词匹配忽略空格，不匹配时返回 false")
    void matchesKeywords() {
        assertThat(service.matches(List.of("慢 SQL"), "慢sql 导致 DB 变慢")).isTrue();
        assertThat(service.matches(List.of("慢SQL"), "缓存击穿")).isFalse();
    }
}
