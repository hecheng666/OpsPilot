package com.opspilot.server.agent.tool;

import com.opspilot.server.agent.EvidenceService;
import com.opspilot.server.agent.ToolContext;
import com.opspilot.server.agent.ToolResult;
import com.opspilot.server.client.DemoClient;
import com.opspilot.server.entity.AuditLog;
import com.opspilot.server.entity.RemediationAction;
import com.opspilot.server.mapper.AuditLogMapper;
import com.opspilot.server.mapper.RemediationActionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecuteRemediationToolTest {

    @Mock
    private RemediationActionMapper actionMapper;
    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private DemoClient demoClient;
    @Mock
    private EvidenceService evidenceService;

    private ExecuteRemediationTool tool;

    @BeforeEach
    void setUp() {
        tool = new ExecuteRemediationTool(actionMapper, auditLogMapper, demoClient, evidenceService);
    }

    @Test
    @DisplayName("缺少 actionId 直接失败，不触碰任何外部资源")
    void rejectsMissingActionId() {
        ToolResult result = tool.execute(Map.of(), adminContext());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("actionId");
        verifyNoInteractions(actionMapper, demoClient, auditLogMapper);
    }

    @Test
    @DisplayName("动作不存在时失败")
    void rejectsUnknownAction() {
        when(actionMapper.selectById(9L)).thenReturn(null);

        ToolResult result = tool.execute(Map.of("actionId", 9L), adminContext());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("不存在");
        verifyNoInteractions(demoClient);
    }

    @Test
    @DisplayName("非 APPROVED 状态拒绝执行，Agent 不能自批")
    void rejectsNonApprovedAction() {
        RemediationAction action = action(9L, "PENDING");
        when(actionMapper.selectById(9L)).thenReturn(action);

        ToolResult result = tool.execute(Map.of("actionId", 9L), adminContext());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("PENDING").contains("APPROVED");
        verify(demoClient, never()).executeAction(any());
        verify(actionMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("APPROVED 动作执行成功，审计记录真实审批人")
    void executesApprovedActionAndAuditsApprover() {
        RemediationAction action = action(9L, "APPROVED");
        when(actionMapper.selectById(9L)).thenReturn(action);
        when(demoClient.executeAction(any())).thenReturn(Map.of("status", "RECOVERED"));
        when(evidenceService.create(anyLong(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("E-1001");

        ToolResult result = tool.execute(Map.of("actionId", 9L), adminContext());

        assertThat(result.success()).isTrue();
        assertThat(result.evidenceId()).isEqualTo("E-1001");
        assertThat(action.getStatus()).isEqualTo("EXECUTED");
        assertThat(action.getExecutedAt()).isNotNull();

        AuditLog audit = captureAudit();
        assertThat(audit.getActor()).isEqualTo("admin");
        assertThat(audit.getAction()).isEqualTo("EXECUTE_REMEDIATION");
        assertThat(audit.getTarget()).isEqualTo("action#9");
        assertThat(audit.getDetail()).contains("RECOVER_FAULT").contains("执行成功");
    }

    @Test
    @DisplayName("demo 执行异常时标记 FAILED，并记录失败审计")
    void marksFailedWhenDemoThrows() {
        RemediationAction action = action(9L, "APPROVED");
        when(actionMapper.selectById(9L)).thenReturn(action);
        when(demoClient.executeAction(any())).thenThrow(new IllegalStateException("boom"));

        ToolResult result = tool.execute(Map.of("actionId", 9L), adminContext());

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("boom");
        assertThat(action.getStatus()).isEqualTo("FAILED");
        assertThat(action.getResult()).contains("boom");

        AuditLog audit = captureAudit();
        assertThat(audit.getActor()).isEqualTo("admin");
        assertThat(audit.getDetail()).contains("执行失败");
    }

    @Test
    @DisplayName("上下文操作人为空时审计回退到 agent")
    void auditFallsBackToAgentWhenActorBlank() {
        RemediationAction action = action(9L, "APPROVED");
        when(actionMapper.selectById(9L)).thenReturn(action);
        when(demoClient.executeAction(any())).thenReturn(Map.of("status", "RECOVERED"));
        when(evidenceService.create(anyLong(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("E-1002");

        ToolContext ctx = new ToolContext(1L, 67L, "order-demo", null, " ");
        tool.execute(Map.of("actionId", 9L), ctx);

        assertThat(captureAudit().getActor()).isEqualTo("agent");
    }

    private AuditLog captureAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static ToolContext adminContext() {
        return ToolContext.ofActor(1L, 67L, "order-demo", "admin");
    }

    private static RemediationAction action(long id, String status) {
        RemediationAction action = new RemediationAction();
        action.setId(id);
        action.setActionType("RECOVER_FAULT");
        action.setTarget("order-demo");
        action.setStatus(status);
        return action;
    }
}
