package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.domain.AgentOutboxStatus;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunOutbox;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentOutboxService {
    private final AgentRunOutboxRepository outbox;

    @Transactional
    public void enqueue(AgentRun run) {
        AgentRunOutbox item = new AgentRunOutbox();
        item.setId(UUID.randomUUID().toString());
        item.setRun(run);
        item.setStatus(AgentOutboxStatus.PENDING);
        item.setNextAttemptAt(Instant.now());
        outbox.save(item);
    }

    @Transactional
    public boolean claim(String runId) {
        AgentRunOutbox item = outbox.findByRunId(runId).orElseThrow();
        if (item.getStatus() != AgentOutboxStatus.PENDING || item.getNextAttemptAt().isAfter(Instant.now())) return false;
        item.setStatus(AgentOutboxStatus.DISPATCHING);
        item.setClaimedAt(Instant.now());
        item.setAttempt(item.getAttempt() + 1);
        return true;
    }

    @Transactional
    public void retryLater(String runId) {
        AgentRunOutbox item = outbox.findByRunId(runId).orElseThrow();
        item.setStatus(AgentOutboxStatus.PENDING);
        item.setNextAttemptAt(Instant.now().plusSeconds(2));
        item.setClaimedAt(null);
    }

    @Transactional
    public void dispatched(String runId) {
        AgentRunOutbox item = outbox.findByRunId(runId).orElseThrow();
        item.setStatus(AgentOutboxStatus.DISPATCHED);
    }

    @Transactional
    public void failed(String runId) {
        AgentRunOutbox item = outbox.findByRunId(runId).orElseThrow();
        item.setStatus(AgentOutboxStatus.FAILED);
    }

    @Transactional(readOnly = true)
    public List<String> readyRunIds() {
        return outbox.findReadyRunIds(AgentOutboxStatus.PENDING, Instant.now(), PageRequest.of(0, 16));
    }

    @Transactional
    public void failInFlightClaims() {
        for (AgentRunOutbox item : outbox.findAllByStatus(AgentOutboxStatus.DISPATCHING)) {
            item.setStatus(AgentOutboxStatus.FAILED);
        }
    }
}
