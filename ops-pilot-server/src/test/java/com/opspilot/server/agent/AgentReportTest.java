package com.opspilot.server.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReportTest {

    private final AgentService service = new AgentService(null, null, null, null, null, null);

    @Test
    @DisplayName("报告中的编造证据编号会被过滤，重复编号去重")
    void filtersFabricatedEvidence() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", "慢 SQL");
        root.put("confidence", 0.8);
        root.put("evidence_ids", List.of("E-1001", "E-9999", "E-1001"));

        Map<String, Object> alt = new LinkedHashMap<>();
        alt.put("title", "缓存击穿");
        alt.put("confidence", 0.3);
        alt.put("evidence_ids", List.of("E-9999", "E-1002"));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("summary", "订单列表变慢");
        raw.put("root_cause", root);
        raw.put("alternatives", List.of(alt));
        raw.put("impact", "订单查询");
        raw.put("prevention", List.of("补充慢查询告警"));

        Map<String, Object> out = service.normalizeReport(raw, Set.of("E-1001", "E-1002"));

        assertThat(evidenceIds(out)).containsExactly("E-1001");
        assertThat(alternativeEvidenceIds(out)).containsExactly("E-1002");
    }

    @Test
    @DisplayName("缺字段时补默认值，动作统一为待审批")
    void fillsContractDefaults() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("root_cause", Map.of("title", "慢 SQL"));
        raw.put("actions", List.of(Map.of("actionId", 77, "risk", "MEDIUM")));

        Map<String, Object> out = service.normalizeReport(raw, Set.of());

        assertThat(out.get("summary")).isEqualTo("（模型未提供摘要）");
        assertThat(out.get("impact")).isEqualTo("");
        assertThat(out.get("alternatives")).isEqualTo(List.of());
        assertThat(out.get("prevention")).isEqualTo(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) out.get("root_cause");
        assertThat(root.get("title")).isEqualTo("慢 SQL");
        assertThat(root.get("confidence")).isEqualTo(0.5);
        assertThat(root.get("evidence_ids")).isEqualTo(List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = (List<Map<String, Object>>) out.get("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("actionId")).isEqualTo(77L);
        assertThat(actions.get(0).get("status")).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("根因不是对象时按字符串处理，并附带本次全部真实证据")
    @SuppressWarnings("unchecked")
    void handlesStringRootCause() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("root_cause", "慢 SQL");

        Map<String, Object> out = service.normalizeReport(raw, Set.of("E-1001", "E-1002"));

        Map<String, Object> root = (Map<String, Object>) out.get("root_cause");
        assertThat(root.get("title")).isEqualTo("慢 SQL");
        assertThat(root.get("confidence")).isEqualTo(0.5);
        assertThat((List<String>) root.get("evidence_ids"))
                .containsExactlyInAnyOrder("E-1001", "E-1002");
    }

    @Test
    @DisplayName("证据过滤只接受列表，其他类型返回空")
    void filterEvidenceRejectsNonList() {
        assertThat(service.filterEvidence(null, Set.of("E-1001"))).isEmpty();
        assertThat(service.filterEvidence("E-1001", Set.of("E-1001"))).isEmpty();
        assertThat(service.filterEvidence(List.of("E-1001"), Set.of("E-1001")))
                .containsExactly("E-1001");
    }

    @SuppressWarnings("unchecked")
    private static List<String> evidenceIds(Map<String, Object> report) {
        return (List<String>) ((Map<String, Object>) report.get("root_cause")).get("evidence_ids");
    }

    @SuppressWarnings("unchecked")
    private static List<String> alternativeEvidenceIds(Map<String, Object> report) {
        List<Map<String, Object>> alts = (List<Map<String, Object>>) report.get("alternatives");
        return (List<String>) alts.get(0).get("evidence_ids");
    }
}
