package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AgentSessionLeaseService {
    private final AgentSessionRepository sessions;

    @Transactional
    public boolean acquire(String sessionId, String runId) {
        Instant now = Instant.now();
        return sessions.tryAcquireLease(sessionId, runId, now, now.plusSeconds(150)) == 1;
    }

    @Transactional
    public void release(String sessionId, String runId) {
        sessions.releaseLease(sessionId, runId);
    }
}
