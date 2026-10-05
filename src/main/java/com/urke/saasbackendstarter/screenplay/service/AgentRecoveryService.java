package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentRecoveryService {
    private final AgentRunRepository runs;
    private final AgentOutboxService outbox;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void interruptRunsLeftOpenByRestart() {
        for (var run : runs.findAllByStatusIn(List.of(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.CANCELLING))) {
            run.setStatus(AgentRunStatus.INTERRUPTED);
            run.setErrorCode("process_restarted");
            run.setEndedAt(Instant.now());
        }
        // Never replay a possibly side-effecting tool call after a process crash.
        // Operators can inspect the interrupted run and its persisted events.
        outbox.failInFlightClaims();
    }
}
