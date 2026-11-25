package com.opspilot.server.agent.tool;

import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.entity.RemediationAction;
import com.opspilot.server.mapper.RemediationActionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProposeRemediationToolTest {

    @Mock
    private RemediationActionMapper actionMapper;
    @Mock
    private EvidenceService evidenceService;

    private ProposeRemediationTool tool;

    @BeforeEach
    void setUp() {
        tool = new ProposeRemediationTool(actionMapper, evidenceService);
        ReflectionTestUtils.setField(tool, "defaultService", "order-demo");
    }

    @Test
    @DisplayName("非法动作类型直接拒绝")
    void rejectsIllegalActionType() {
        ToolResult result = tool.execute(Map.of("actionType", "DROP_DATABASE", "reason", "x"), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("actionType");
        verifyNoInteractions(actionMapper);
    }

    @Test
    @DisplayName("缺少 reason 直接拒绝")
    void rejectsMissingReason() {
        ToolResult result = tool.execute(Map.of("actionType", "RECOVER_FAULT"), ctx());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("reason");
        verifyNoInteractions(actionMapper);
    }

    @Test
    @DisplayName("合法提议只创建 PENDING 动作，默认目标与风险按规则推断")
    void createsPendingActionWithDefaults() {
        stubInsertAndEvidence(77L, "E-1002");

        ToolResult result = tool.execute(Map.of(
                "actionType", "RECOVER_FAULT",
                "reason", "慢 SQL 导致 DB RT 升高"), ctx());

        assertThat(result.success()).isTrue();
        assertThat(result.evidenceId()).isEqualTo("E-1002");

        RemediationAction saved = captureInsertedAction();
        assertThat(saved.getId()).isEqualTo(77L);
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getCreatedBy()).isEqualTo("agent");
        assertThat(saved.getTarget()).isEqualTo("order-demo");
        assertThat(saved.getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(saved.getIncidentId()).isEqualTo(67L);
        assertThat(saved.getRunId()).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data).containsEntry("actionId", 77L).containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("显式目标、风险与 incidentId 覆盖默认值，params 序列化为 JSON")
    void honorsExplicitArguments() {
        stubInsertAndEvidence(78L, "E-1003");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("actionType", "ENABLE_RATE_LIMIT");
        args.put("reason", "下游超时，先限流");
        args.put("target", "inventory-demo");
        args.put("riskLevel", "HIGH");
        args.put("incidentId", 99L);
        args.put("params", Map.of("type", "DOWNSTREAM_TIMEOUT"));

        ToolResult result = tool.execute(args, ctx());

        assertThat(result.success()).isTrue();
        RemediationAction saved = captureInsertedAction();
        assertThat(saved.getTarget()).isEqualTo("inventory-demo");
        assertThat(saved.getRiskLevel()).isEqualTo("HIGH");
        assertThat(saved.getIncidentId()).isEqualTo(99L);
        assertThat(saved.getParams()).contains("DOWNSTREAM_TIMEOUT");
    }

    private void stubInsertAndEvidence(long actionId, String evidenceId) {
        when(actionMapper.insert(any())).thenAnswer(invocation -> {
            RemediationAction action = invocation.getArgument(0);
            action.setId(actionId);
            return 1;
        });
        when(evidenceService.create(anyLong(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(evidenceId);
    }

    private RemediationAction captureInsertedAction() {
        ArgumentCaptor<RemediationAction> captor = ArgumentCaptor.forClass(RemediationAction.class);
        verify(actionMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static ToolContext ctx() {
        return ToolContext.ofActor(1L, 67L, "order-demo", "agent");
    }
}
