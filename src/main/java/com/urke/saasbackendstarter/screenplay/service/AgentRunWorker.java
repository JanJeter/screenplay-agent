package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionClaims;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionTokenService;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentSessionRepository;
import com.urke.saasbackendstarter.screenplay.repository.StoryboardRunContextRepository;
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
    private final StoryboardRunContextRepository storyboardContexts;

    @Transactional
    public AgentRunService.GatewayDispatch start(String runId) {
        AgentRun run = requiredLocked(runId);
        if (run.getStatus() != AgentRunStatus.QUEUED) return null;
        if (!leases.acquire(run.getSession().getId(), run.getId())) return null;
        run.setStatus(AgentRunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        var session = run.getSession();
        var script = session.getScript();
        Set<String> scopes = run.getSession().getTaskType().requiresStoryboardArtifact()
                ? (run.getSession().getTaskType() == com.urke.saasbackendstarter.screenplay.domain.AgentTaskType.GENERATE_STORYBOARD
                    ? Set.of("storyboard:read", "storyboard:create") : Set.of("storyboard:read", "shot-proposal:create"))
                : Set.of("screenplay:read", "draft:create", "task:create");
        String executionToken = tokens.issue(new AgentExecutionClaims(session.getOrganization().getId(), session.getUser().getId(),
                session.getProject().getId(), script.getId(), run.getId(), session.getId(),
                scopes));
        return new AgentRunService.GatewayDispatch(session.getUser().getId(), session.getProject().getId(), script.getId(),
                session.getId(), run.getId(), executionToken, run.getRequestId(), transcript.promptWithRecentHistory(run),
                session.getTaskType().name().toLowerCase());
    }

    @Transactional
    public GatewayBinding bindGateway(String runId, String gatewaySessionId, String gatewayRunId) {
        AgentRun run = requiredLocked(runId);
        if (isTerminal(run.getStatus())) return new GatewayBinding(null, null, false);
        run.getSession().setGatewaySessionId(gatewaySessionId);
        run.setGatewayRunId(gatewayRunId);
        return new GatewayBinding(gatewayRunId, run.getSession().getUser().getId(),
                run.getStatus() == AgentRunStatus.CANCELLING);
    }

    @Transactional
    public void terminal(String runId, String gatewayType) {
        AgentRun run = requiredLocked(runId);
        if (isTerminal(run.getStatus())) return;
        AgentRunStatus status;
        String error = null;
        if ("run.cancelled".equals(gatewayType) || run.getStatus() == AgentRunStatus.CANCELLING) {
            status = AgentRunStatus.CANCELLED;
        } else if ("run.completed".equals(gatewayType)) {
            if (run.getSession().getTaskType().requiresStoryboardArtifact()) {
                if (storyboardContexts.existsByRunIdAndArtifactIdIsNotNull(runId) && run.getResultId() != null) status = AgentRunStatus.COMPLETED;
                else { status = AgentRunStatus.FAILED; error = "storyboard_result_missing"; }
            } else if (!run.getSession().getTaskType().requiresDraft() || drafts.existsByRunId(runId)) status = AgentRunStatus.COMPLETED;
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
        AgentRun run = requiredLocked(runId);
        if (isTerminal(run.getStatus())) return;
        if (run.getStatus() == AgentRunStatus.CANCELLING) {
            run.setStatus(AgentRunStatus.CANCELLED);
            run.setErrorCode(null);
            run.setEndedAt(Instant.now());
            leases.release(run.getSession().getId(), run.getId());
            return;
        }
        run.setStatus(AgentRunStatus.FAILED);
        run.setErrorCode(errorCode);
        run.setEndedAt(Instant.now());
        leases.release(run.getSession().getId(), run.getId());
    }

    @Transactional
    public String requestCancellation(String runId) {
        AgentRun run = requiredLocked(runId);
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

    private AgentRun requiredLocked(String id) {
        return runs.findLockedById(id).orElseThrow(() -> new IllegalArgumentException("Agent run not found"));
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED || status == AgentRunStatus.INTERRUPTED;
    }

    public record GatewayReference(String gatewayRunId, Long userId, AgentRunStatus status) { }
    public record GatewayBinding(String gatewayRunId, Long userId, boolean cancellationRequested) { }
}
