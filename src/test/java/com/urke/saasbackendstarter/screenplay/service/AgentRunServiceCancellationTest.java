package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.domain.Organization;
import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.screenplay.agent.AgentGatewayClient;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus;
import com.urke.saasbackendstarter.screenplay.domain.AgentSession;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentSessionRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScreenplayProjectRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceCancellationTest {
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

    @Test
    void takes_the_ownership_lock_before_any_cancellation_state_transition() {
        User user = user();
        AgentRun run = run(user);
        when(currentUser.getCurrentUser()).thenReturn(user);
        when(runs.findLockedByIdAndSessionOrganizationIdAndSessionUserId("run-1", 1L, 2L)).thenReturn(Optional.of(run));
        when(runs.findAllLockedByParentRunIdAndStatusIn(org.mockito.ArgumentMatchers.eq("run-1"), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(worker.requestCancellation("run-1")).thenReturn(null);
        when(runs.findByIdAndSessionOrganizationIdAndSessionUserId("run-1", 1L, 2L)).thenReturn(Optional.of(run));

        service().cancel("run-1");

        InOrder order = inOrder(runs, worker);
        order.verify(runs).findLockedByIdAndSessionOrganizationIdAndSessionUserId("run-1", 1L, 2L);
        order.verify(runs).findAllLockedByParentRunIdAndStatusIn(org.mockito.ArgumentMatchers.eq("run-1"), org.mockito.ArgumentMatchers.any());
        order.verify(worker).requestCancellation("run-1");
    }

    private AgentRunService service() {
        return new AgentRunService(currentUser, projects, scripts, sessions, runs, worker, transcript,
                events, broadcaster, outbox, dispatcher, gateway, entityManager);
    }

    private User user() {
        Organization organization = new Organization();
        organization.setId(1L);
        User user = new User();
        user.setId(2L);
        user.setOrganization(organization);
        return user;
    }

    private AgentRun run(User user) {
        AgentSession session = new AgentSession();
        session.setId("session-1");
        session.setOrganization(user.getOrganization());
        session.setUser(user);
        AgentRun run = new AgentRun();
        run.setId("run-1");
        run.setSession(session);
        run.setStatus(AgentRunStatus.RUNNING);
        return run;
    }
}
