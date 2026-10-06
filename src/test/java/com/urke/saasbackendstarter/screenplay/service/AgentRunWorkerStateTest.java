package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionTokenService;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus;
import com.urke.saasbackendstarter.screenplay.domain.AgentSession;
import com.urke.saasbackendstarter.screenplay.domain.AgentTaskType;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentSessionRepository;
import com.urke.saasbackendstarter.screenplay.repository.StoryboardRunContextRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunWorkerStateTest {
    @Mock private AgentRunRepository runs;
    @Mock private AgentSessionRepository sessions;
    @Mock private AgentDraftRepository drafts;
    @Mock private AgentExecutionTokenService tokens;
    @Mock private AgentTranscriptService transcript;
    @Mock private AgentSessionLeaseService leases;
    @Mock private StoryboardRunContextRepository storyboardContexts;

    @Test
    void cancellation_before_gateway_binding_is_forwarded_once_a_gateway_id_exists() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        when(runs.findLockedById("run-1")).thenReturn(Optional.of(run));

        assertThat(worker().requestCancellation("run-1")).isNull();
        var binding = worker().bindGateway("run-1", "gateway-session", "gateway-run");

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELLING);
        assertThat(binding.gatewayRunId()).isEqualTo("gateway-run");
        assertThat(binding.userId()).isEqualTo(9L);
        assertThat(binding.cancellationRequested()).isTrue();
    }

    @Test
    void terminal_state_cannot_be_overwritten_by_a_late_cancellation() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        when(runs.findLockedById("run-1")).thenReturn(Optional.of(run));

        worker().terminal("run-1", "run.completed");
        worker().requestCancellation("run-1");

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
    }

    @Test
    void cancellation_wins_when_it_holds_the_shared_transition_lock_first() {
        AgentRun run = run(AgentRunStatus.RUNNING);
        when(runs.findLockedById("run-1")).thenReturn(Optional.of(run));

        worker().requestCancellation("run-1");
        worker().terminal("run-1", "run.completed");

        assertThat(run.getStatus()).isEqualTo(AgentRunStatus.CANCELLED);
    }

    private AgentRunWorker worker() {
        return new AgentRunWorker(runs, sessions, drafts, tokens, transcript, leases, storyboardContexts);
    }

    private AgentRun run(AgentRunStatus status) {
        User user = new User();
        user.setId(9L);
        AgentSession session = new AgentSession();
        session.setId("session-1");
        session.setUser(user);
        session.setTaskType(AgentTaskType.GENERAL);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setSession(session);
        run.setStatus(status);
        return run;
    }
}
