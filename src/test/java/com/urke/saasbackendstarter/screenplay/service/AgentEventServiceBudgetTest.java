package com.urke.saasbackendstarter.screenplay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus;
import com.urke.saasbackendstarter.screenplay.domain.AgentSession;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentEventRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentEventServiceBudgetTest {
    @Mock AgentEventRepository events;
    @Mock AgentRunRepository runs;
    @Mock AgentDraftRepository drafts;
    @Mock AgentSessionLeaseService leases;
    @Mock AgentTranscriptService transcript;
    @Mock EntityManager entityManager;
    @Mock Query lockQuery;
    private final ObjectMapper json = new ObjectMapper();
    private AgentRun run;
    private AgentEventService service;

    @BeforeEach
    void setUp() {
        AgentSession session = new AgentSession();
        session.setId("session-budget");
        run = new AgentRun();
        run.setId("run-budget");
        run.setSession(session);
        run.setStatus(AgentRunStatus.RUNNING);
        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(eq(1), any())).thenReturn(lockQuery);
        when(runs.findLockedById(run.getId())).thenReturn(Optional.of(run));
        service = new AgentEventService(events, runs, drafts, leases, transcript, json, entityManager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"budget_exhausted", "business_run_limit", "business_run_duplicate",
            "budget_context_limit", "budget_uncertain", "storyboard_source_quote_invalid",
            "storyboard_result_invalid", "storyboard_save_failed", "invalid_structured_output"})
    void public_gateway_failure_is_persisted_for_both_run_refresh_and_sse_replay(String code) {
        var payload = json.createObjectNode();
        payload.putObject("data").put("code", code);

        var persisted = service.recordTerminal(run.getId(), 3, "run.failed", payload);

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.getErrorCode()).isEqualTo(code);
        assertThat(run.getEndedAt()).isNotNull();
        assertThat(persisted.type()).isEqualTo("run.failed");
        assertThat(persisted.payload().path("errorCode").asText()).isEqualTo(code);
        assertThat(payload.has("errorCode")).isFalse();
        verify(events).save(any());
        verify(leases).release("session-budget", "run-budget");
    }

    @Test
    void unknown_provider_reason_uses_generic_public_error_code() {
        var payload = json.createObjectNode();
        payload.putObject("data").put("code", "internal-provider-detail");
        var persisted = service.recordTerminal(run.getId(), 3, "run.failed", payload);
        assertThat(run.getErrorCode()).isEqualTo("gateway_failed");
        assertThat(persisted.payload().path("errorCode").asText()).isEqualTo("gateway_failed");
    }

    @Test
    void cancellation_wins_over_late_budget_failure() {
        run.setStatus(AgentRunStatus.CANCELLING);
        var payload = json.createObjectNode();
        payload.putObject("data").put("code", "budget_exhausted");
        var persisted = service.recordTerminal(run.getId(), 3, "run.failed", payload);
        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(run.getErrorCode()).isNull();
        assertThat(persisted.type()).isEqualTo("run.cancelled");
    }

    @ParameterizedTest
    @ValueSource(strings = {"business_run_limit", "gateway_failed"})
    void terminal_run_keeps_original_failure_after_a_late_gateway_event(String originalCode) {
        run.setStatus(AgentRunStatus.FAILED);
        run.setErrorCode(originalCode);
        var payload = json.createObjectNode();
        payload.putObject("data").put("code", "storyboard_source_quote_invalid");
        var persisted = service.recordTerminal(run.getId(), 3, "run.failed", payload);
        assertThat(run.getErrorCode()).isEqualTo(originalCode);
        assertThat(persisted.payload().path("errorCode").asText()).isEqualTo(originalCode);
    }
}
