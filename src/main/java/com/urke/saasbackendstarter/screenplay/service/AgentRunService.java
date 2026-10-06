package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.screenplay.agent.AgentGatewayClient;
import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.dto.agent.*;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentSessionRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScreenplayProjectRepository;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import jakarta.persistence.EntityManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentRunService {
    private final CurrentUserProvider currentUser;
    private final ScreenplayProjectRepository projects;
    private final ScriptVersionRepository scripts;
    private final AgentSessionRepository sessions;
    private final AgentRunRepository runs;
    private final AgentRunWorker worker;
    private final AgentTranscriptService transcript;
    private final AgentEventService events;
    private final AgentEventBroadcaster broadcaster;
    private final AgentOutboxService outbox;
    private final AgentGatewayDispatchService dispatcher;
    private final AgentGatewayClient gateway;
    private final EntityManager entityManager;
    @jakarta.annotation.Resource(name = "agentControlExecutor")
    private AsyncTaskExecutor controlExecutor;

    @Transactional
    public AgentSessionResponse createSession(Long projectId, CreateAgentSessionRequest request) {
        User user = currentUser.getCurrentUser();
        Long orgId = organizationId(user);
        ScreenplayProject project = projects.findByIdAndOrganizationId(projectId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        ScriptVersion script = scripts.findByIdAndProjectOrganizationId(request.screenplayId(), orgId)
                .filter(candidate -> candidate.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay not found"));
        AgentSession session = new AgentSession();
        session.setId(UUID.randomUUID().toString());
        session.setOrganization(user.getOrganization());
        session.setUser(user);
        session.setProject(project);
        session.setScript(script);
        session.setProfileVersion("v1");
        try { session.setTaskType(AgentTaskType.fromApi(request.taskType())); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported taskType"); }
        sessions.save(session);
        return new AgentSessionResponse(session.getId(), Long.toString(projectId), Long.toString(script.getId()),
                session.getTaskType().name().toLowerCase(), "ready");
    }

    @Transactional
    public AgentRunResponse submit(String sessionId, CreateAgentMessageRequest request) {
        User user = currentUser.getCurrentUser();
        AgentSession session = ownedSession(sessionId, user);
        // Serialize every new submission for one session. The idempotency lookup
        // remains per requestId, but different requests must not both pass the
        // active-run check before either inserts its run.
        advisoryLock("run:" + organizationId(user) + ":" + sessionId);
        String requestHash = hash(request.message());
        var existing = runs.findBySessionIdAndRequestId(sessionId, request.clientRequestId());
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(requestHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key has a different message");
            }
            return response(existing.get());
        }
        if (runs.existsBySessionIdAndStatusIn(sessionId,
                java.util.List.of(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.FINALIZING, AgentRunStatus.CANCELLING))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent session already has an active run");
        }
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setSession(session);
        run.setAgentId(session.getTaskType().name().toLowerCase());
        run.setTraceId(UUID.randomUUID().toString());
        run.setRequestId(request.clientRequestId());
        run.setRequestHash(requestHash);
        run.setRequestMessage(request.message());
        run.setSourceRevision(session.getScript().getContentRevision());
        run.setStatus(AgentRunStatus.QUEUED);
        runs.saveAndFlush(run);
        transcript.append(run, "user", request.message());
        outbox.enqueue(run);
        String runId = run.getId();
        // The worker must never observe a run before this short creation
        // transaction is committed; retries can safely use the same request id.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { dispatcher.dispatchAsync(runId); }
        });
        return response(run);
    }

    @Transactional
    public AgentRunResponse createSubtask(String parentRunId, CreateAgentSubtaskRequest request) {
        User user = currentUser.getCurrentUser();
        AgentRun parent = ownedRun(parentRunId, user);
        return createSubtask(parent, request);
    }

    @Transactional
    public AgentRunResponse createSubtaskForExecution(AgentRun parent, CreateAgentSubtaskRequest request) {
        return createSubtask(parent, request);
    }

    private AgentRunResponse createSubtask(AgentRun parent, CreateAgentSubtaskRequest request) {
        var existing = runs.findByParentRunIdAndRequestId(parent.getId(), request.clientRequestId());
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(hash(request.message()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key has a different message");
            }
            return response(existing.get());
        }
        if (parent.getParentRun() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subtask delegation depth is limited to one");
        }
        if (runs.countByParentRunId(parent.getId()) >= 4) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Subtask limit reached for this run");
        }
        AgentTaskType taskType;
        try { taskType = AgentTaskType.fromApi(request.taskType()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported taskType"); }
        if (taskType == AgentTaskType.GENERAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subtask taskType must be specialized");
        }
        AgentSession parentSession = parent.getSession();
        AgentSession session = new AgentSession();
        session.setId(UUID.randomUUID().toString());
        session.setOrganization(parentSession.getOrganization());
        session.setUser(parentSession.getUser());
        session.setProject(parentSession.getProject());
        session.setScript(parentSession.getScript());
        session.setProfileVersion(parentSession.getProfileVersion());
        session.setTaskType(taskType);
        sessions.save(session);

        AgentRun child = new AgentRun();
        child.setId(UUID.randomUUID().toString());
        child.setSession(session);
        child.setParentRun(parent);
        child.setAgentId(taskType.name().toLowerCase());
        child.setTraceId(parent.getTraceId());
        child.setRequestId(request.clientRequestId());
        child.setRequestHash(hash(request.message()));
        child.setRequestMessage(request.message());
        child.setSourceRevision(session.getScript().getContentRevision());
        child.setStatus(AgentRunStatus.QUEUED);
        runs.saveAndFlush(child);
        transcript.append(child, "user", request.message());
        outbox.enqueue(child);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { dispatcher.dispatchAsync(child.getId()); }
        });
        return response(child);
    }

    @Transactional(readOnly = true)
    public AgentRunResponse getRun(String runId) {
        return response(ownedRun(runId, currentUser.getCurrentUser()));
    }

    @Transactional(readOnly = true)
    public SseEmitter events(String runId, String lastEventId) {
        ownedRun(runId, currentUser.getCurrentUser());
        long after = cursor(lastEventId);
        return broadcaster.subscribe(runId, after);
    }

    @Transactional
    public AgentRunResponse cancel(String runId) {
        // Take the row lock on the first read. Re-locking after an ordinary
        // managed lookup does not refresh that old entity, which can otherwise
        // flush CANCELLING over a terminal state committed in another transaction.
        AgentRun run = ownedRunLocked(runId, currentUser.getCurrentUser());
        java.util.List<AgentRun> cancellationTargets = new java.util.ArrayList<>();
        cancellationTargets.add(run);
        cancellationTargets.addAll(runs.findAllLockedByParentRunIdAndStatusIn(runId,
                java.util.List.of(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING, AgentRunStatus.FINALIZING, AgentRunStatus.CANCELLING)));
        java.util.List<GatewayCancellation> cancellations = new java.util.ArrayList<>();
        for (AgentRun target : cancellationTargets) {
            String gatewayRunId = worker.requestCancellation(target.getId());
            if (gatewayRunId != null) cancellations.add(new GatewayCancellation(gatewayRunId, target.getSession().getUser().getId(), target.getId()));
        }
        if (!cancellations.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    for (GatewayCancellation cancellation : cancellations) controlExecutor.execute(() -> {
                        try { gateway.cancel(cancellation.gatewayRunId(), cancellation.userId()); }
                        catch (RuntimeException ex) { worker.failure(cancellation.runId(), "gateway_cancel_failed"); }
                    });
                }
            });
        }
        return getRun(runId);
    }


    private AgentSession ownedSession(String id, User user) {
        return sessions.findByIdAndOrganizationIdAndUserId(id, organizationId(user), user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent session not found"));
    }

    private AgentRun ownedRun(String id, User user) {
        return runs.findByIdAndSessionOrganizationIdAndSessionUserId(id, organizationId(user), user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run not found"));
    }

    private AgentRun ownedRunLocked(String id, User user) {
        return runs.findLockedByIdAndSessionOrganizationIdAndSessionUserId(id, organizationId(user), user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent run not found"));
    }

    private Long organizationId(User user) {
        if (user.getOrganization() == null || user.getOrganization().getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization membership is required");
        }
        return user.getOrganization().getId();
    }

    private AgentRunResponse response(AgentRun run) {
        return new AgentRunResponse(run.getId(), run.getSession().getId(),
                run.getParentRun() == null ? null : run.getParentRun().getId(), run.getAgentId(), run.getTraceId(), run.getStatus().name().toLowerCase(),
                run.getErrorCode(), run.getCreatedAt(), run.getStartedAt(), run.getEndedAt(),
                run.getResultId() == null ? null : new AgentRunResponse.ResultRef(run.getResultType(), run.getResultId(), run.getResultStoryboardId()));
    }

    private long cursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try { long cursor = Long.parseLong(value); if (cursor >= 0) return cursor; }
        catch (NumberFormatException ignored) { }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Last-Event-ID");
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }

    /** PostgreSQL transaction lock closes the find-then-insert idempotency race. */
    private void advisoryLock(String key) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, key).getSingleResult();
    }

    public record GatewayDispatch(Long userId, Long projectId, Long screenplayId, String applicationSessionId,
                                  String applicationRunId, String executionToken, String requestId, String message,
                                  String taskType) { }
    private record GatewayCancellation(String gatewayRunId, Long userId, String runId) { }
}
