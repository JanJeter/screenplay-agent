package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.agent.AgentGatewayClient;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentSession;
import com.urke.saasbackendstarter.screenplay.domain.AgentTaskType;
import com.urke.saasbackendstarter.screenplay.domain.ScriptVersion;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentRunResponse;
import com.urke.saasbackendstarter.screenplay.dto.agent.CreateAgentSubtaskRequest;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentSessionRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScreenplayProjectRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceSubtaskTest {
    @Mock private CurrentUserProvider currentUser;
    @Mock private ScreenplayProjectRepository projects;
    @Mock private ScriptVersionRepository scripts;
    @Mock private AgentSessionRepository sessions;
    @Mock private AgentRunRepository runs;
    @Mock private AgentRunWorker worker;
    @Mock private AgentTranscriptService transcript;
    @Mock private AgentEventService events;
    @Mock private AgentEventBroadcaster broadcaster;
    @Mock private AgentOutboxService outbox;
    @Mock private AgentGatewayDispatchService dispatcher;
    @Mock private AgentGatewayClient gateway;
    @Mock private EntityManager entityManager;
    @Mock private AsyncTaskExecutor executor;

    private AgentRunService service;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        service = new AgentRunService(currentUser, projects, scripts, sessions, runs, worker, transcript,
                events, broadcaster, outbox, dispatcher, gateway, entityManager);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void createsSpecializedChildWithIndependentSessionAndSharedTrace() {
        AgentRun parent = parentRun();
        when(runs.findByParentRunIdAndRequestId(parent.getId(), "delegate-1")).thenReturn(Optional.empty());
        when(runs.countByParentRunId(parent.getId())).thenReturn(0L);

        AgentRunResponse result = service.createSubtaskForExecution(parent,
                new CreateAgentSubtaskRequest("analyze_scene", "找出第一场的冲突", "delegate-1"));

        ArgumentCaptor<AgentSession> sessionCaptor = ArgumentCaptor.forClass(AgentSession.class);
        ArgumentCaptor<AgentRun> childCaptor = ArgumentCaptor.forClass(AgentRun.class);
        verify(sessions).save(sessionCaptor.capture());
        verify(runs).saveAndFlush(childCaptor.capture());
        verify(transcript).append(childCaptor.getValue(), "user", "找出第一场的冲突");
        verify(outbox).enqueue(childCaptor.getValue());

        AgentSession childSession = sessionCaptor.getValue();
        AgentRun child = childCaptor.getValue();
        assertThat(childSession.getId()).isNotEqualTo(parent.getSession().getId());
        assertThat(childSession.getTaskType()).isEqualTo(AgentTaskType.ANALYZE_SCENE);
        assertThat(child.getParentRun()).isSameAs(parent);
        assertThat(child.getTraceId()).isEqualTo("trace-parent");
        assertThat(result.parentRunId()).isEqualTo(parent.getId());
        assertThat(result.agentId()).isEqualTo("analyze_scene");
    }

    @Test
    void returnsExistingChildForSameParentRequestAndMessage() {
        AgentRun parent = parentRun();
        AgentRun existing = new AgentRun();
        existing.setId("child-existing");
        existing.setSession(parent.getSession());
        existing.setParentRun(parent);
        existing.setAgentId("check_plot_logic");
        existing.setTraceId("trace-parent");
        existing.setRequestHash(sha256("检查时间线"));
        when(runs.findByParentRunIdAndRequestId(parent.getId(), "delegate-1")).thenReturn(Optional.of(existing));

        AgentRunResponse result = service.createSubtaskForExecution(parent,
                new CreateAgentSubtaskRequest("check_plot_logic", "检查时间线", "delegate-1"));

        assertThat(result.id()).isEqualTo("child-existing");
        verifyNoInteractions(sessions, transcript, outbox, dispatcher);
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void rejectsNestedDelegationAndParentChildLimit() {
        AgentRun nestedParent = parentRun();
        nestedParent.setParentRun(new AgentRun());
        when(runs.findByParentRunIdAndRequestId(nestedParent.getId(), "delegate-1")).thenReturn(Optional.empty());

        assertConflict(() -> service.createSubtaskForExecution(nestedParent,
                new CreateAgentSubtaskRequest("build_outline", "整理故事大纲", "delegate-1")), "depth");

        AgentRun parent = parentRun();
        when(runs.findByParentRunIdAndRequestId(parent.getId(), "delegate-2")).thenReturn(Optional.empty());
        when(runs.countByParentRunId(parent.getId())).thenReturn(4L);
        assertConflict(() -> service.createSubtaskForExecution(parent,
                new CreateAgentSubtaskRequest("build_outline", "整理故事大纲", "delegate-2")), "limit");
        verifyNoInteractions(sessions, transcript, outbox, dispatcher);
    }

    @Test
    void rejectsGeneralProfileAndReusedKeyWithDifferentMessage() {
        AgentRun parent = parentRun();
        when(runs.findByParentRunIdAndRequestId(parent.getId(), "delegate-1")).thenReturn(Optional.empty());
        when(runs.countByParentRunId(parent.getId())).thenReturn(0L);
        assertThatThrownBy(() -> service.createSubtaskForExecution(parent,
                new CreateAgentSubtaskRequest("general", "不允许泛化委派", "delegate-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        AgentRun existing = new AgentRun();
        existing.setRequestHash(sha256("第一次请求"));
        when(runs.findByParentRunIdAndRequestId(parent.getId(), "delegate-2")).thenReturn(Optional.of(existing));
        assertConflict(() -> service.createSubtaskForExecution(parent,
                new CreateAgentSubtaskRequest("analyze_scene", "不同内容", "delegate-2")), "Idempotency");
    }

    private AgentRun parentRun() {
        ScriptVersion script = new ScriptVersion();
        script.setContentRevision(7);
        AgentSession session = new AgentSession();
        session.setId("parent-session");
        session.setScript(script);
        session.setTaskType(AgentTaskType.GENERAL);
        AgentRun parent = new AgentRun();
        parent.setId("parent-run");
        parent.setSession(session);
        parent.setTraceId("trace-parent");
        return parent;
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String message) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(ex.getReason()).containsIgnoringCase(message);
        });
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
