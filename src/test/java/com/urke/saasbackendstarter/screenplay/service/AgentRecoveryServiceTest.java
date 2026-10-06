package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus;
import com.urke.saasbackendstarter.screenplay.domain.AgentSession;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentRecoveryServiceTest {
    @Mock private AgentRunRepository runs;
    @Mock private AgentOutboxService outbox;
    @Mock private AgentSessionLeaseService leases;

    @Test
    void restart_interrupts_every_open_run_releases_its_lease_and_never_touches_terminal_runs() {
        AgentRun queued = run("queued", AgentRunStatus.QUEUED);
        AgentRun running = run("running", AgentRunStatus.RUNNING);
        AgentRun finalizing = run("finalizing", AgentRunStatus.FINALIZING);
        AgentRun cancelling = run("cancelling", AgentRunStatus.CANCELLING);
        when(runs.findAllByStatusIn(anyCollection())).thenReturn(List.of(queued, running, finalizing, cancelling));

        recovery().interruptRunsLeftOpenByRestart();

        for (AgentRun run : List.of(queued, running, finalizing, cancelling)) {
            assertThat(run.getStatus()).isEqualTo(AgentRunStatus.INTERRUPTED);
            assertThat(run.getErrorCode()).isEqualTo("process_restarted");
            assertThat(run.getEndedAt()).isNotNull();
            verify(leases).release(run.getSession().getId(), run.getId());
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentRunStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(runs).findAllByStatusIn(statuses.capture());
        assertThat(statuses.getValue()).containsExactly(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.FINALIZING, AgentRunStatus.CANCELLING);
        verify(outbox).failInFlightClaims();
        verifyNoMoreInteractions(leases);
    }

    @Test
    void restart_still_fails_claimed_outbox_work_when_there_are_no_open_runs() {
        when(runs.findAllByStatusIn(anyCollection())).thenReturn(List.of());

        recovery().interruptRunsLeftOpenByRestart();

        verifyNoInteractions(leases);
        verify(outbox).failInFlightClaims();
    }

    private AgentRecoveryService recovery() {
        return new AgentRecoveryService(runs, outbox, leases);
    }

    private AgentRun run(String id, AgentRunStatus status) {
        AgentSession session = new AgentSession();
        session.setId("session-" + id);
        AgentRun run = new AgentRun();
        run.setId("run-" + id);
        run.setSession(session);
        run.setStatus(status);
        return run;
    }
}
