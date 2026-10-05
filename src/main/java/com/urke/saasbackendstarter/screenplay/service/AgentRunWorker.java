package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionClaims;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionTokenService;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/** Short transactional state transitions around the otherwise non-transactional Gateway I/O. */
@Service
@RequiredArgsConstructor
public class AgentRunWorker {
    private final AgentRunRepository runs;
    private final AgentSessionRepository sessions;
    private final AgentDraftRepository drafts;
    private final AgentExecutionTokenService tokens;
    private final AgentTranscriptService transcript;
    private final AgentSessionLeaseService leases;

    @Transactional
    public AgentRunService.GatewayDispatch start(String runId) {
        AgentRun run = required(runId);
        if (run.getStatus() != AgentRunStatus.QUEUED) return null;
        if (!leases.acquire(run.getSession().getId(), run.getId())) return null;
        run.setStatus(AgentRunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        var session = run.getSession();
        var script = session.getScript();
        String executionToken = tokens.issue(new AgentExecutionClaims(session.getOrganization().getId(), session.getUser().getId(),
                session.getProject().getId(), script.getId(), run.getId(), session.getId(),
                Set.of("screenplay:read", "draft:create", "task:create")));
        return new AgentRunService.GatewayDispatch(session.getUser().getId(), session.getProject().getId(), script.getId(),
                session.getId(), run.getId(), executionToken, run.getRequestId(), transcript.promptWithRecentHistory(run),
                session.getTaskType().name().toLowerCase());
    }

    @Transactional
    public void bindGateway(String runId, String gatewaySessionId, String gatewayRunId) {
        AgentRun run = required(runId);
        if (run.getStatus() != AgentRunStatus.RUNNING) return;
        run.getSession().setGatewaySessionId(gatewaySessionId);
        run.setGatewayRunId(gatewayRunId);
    }

    @Transactional
    public void terminal(String runId, String gatewayType) {
        AgentRun run = required(runId);
        if (isTerminal(run.getStatus())) return;
        AgentRunStatus status;
        String error = null;
        if ("run.cancelled".equals(gatewayType) || run.getStatus() == AgentRunStatus.CANCELLING) {
            status = AgentRunStatus.CANCELLED;
        } else if ("run.completed".equals(gatewayType)) {
            if (!run.getSession().getTaskType().requiresDraft() || drafts.existsByRunId(runId)) status = AgentRunStatus.COMPLETED;
            else { status = AgentRunStatus.FAILED; error = "draft_missing"; }
        } else {
            status = AgentRunStatus.FAILED;
            error = "gateway_failed";
        }
        run.setStatus(status);
        run.setErrorCode(error);
        run.setEndedAt(Instant.now());
        leases.release(run.getSession().getId(), run.getId());
    }

    @Transactional
    public void failure(String runId, String errorCode) {
        AgentRun run = required(runId);
        if (isTerminal(run.getStatus())) return;
        run.setStatus(AgentRunStatus.FAILED);
        run.setErrorCode(errorCode);
        run.setEndedAt(Instant.now());
        leases.release(run.getSession().getId(), run.getId());
    }

    @Transactional
    public String requestCancellation(String runId) {
        AgentRun run = required(runId);
        if (run.getStatus() == AgentRunStatus.QUEUED) {
            run.setStatus(AgentRunStatus.CANCELLED);
            run.setEndedAt(Instant.now());
        } else if (!isTerminal(run.getStatus())) {
            run.setStatus(AgentRunStatus.CANCELLING);
        }
        return run.getGatewayRunId();
    }

    @Transactional(readOnly = true)
    public GatewayReference gatewayReference(String runId) {
        AgentRun run = required(runId);
        return new GatewayReference(run.getGatewayRunId(), run.getSession().getUser().getId(), run.getStatus());
    }

    private AgentRun required(String id) {
        return runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Agent run not found"));
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED || status == AgentRunStatus.INTERRUPTED;
    }

    public record GatewayReference(String gatewayRunId, Long userId, AgentRunStatus status) { }
}
