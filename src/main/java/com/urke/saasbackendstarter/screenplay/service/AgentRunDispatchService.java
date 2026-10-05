package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionClaims;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionTokenService;
import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentSessionRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScreenplayProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;

/**
 * Creates the durable business context before a worker calls the Gateway.
 * It intentionally does no network I/O; P3 can invoke it in a short transaction.
 */
@Service
@RequiredArgsConstructor
public class AgentRunDispatchService {
    private final ScreenplayProjectRepository projects;
    private final ScriptVersionRepository scripts;
    private final AgentSessionRepository sessions;
    private final AgentRunRepository runs;
    private final AgentExecutionTokenService tokens;

    @Transactional
    public PreparedAgentRun prepare(User user, Long projectId, Long screenplayId, String requestId, String requestPayload) {
        if (user.getOrganization() == null || user.getOrganization().getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization membership is required");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request id");
        }
        Long organizationId = user.getOrganization().getId();
        ScreenplayProject project = projects.findByIdAndOrganizationId(projectId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        ScriptVersion script = scripts.findByIdAndProjectOrganizationId(screenplayId, organizationId)
                .filter(candidate -> candidate.getProject().getId().equals(project.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay not found"));

        AgentSession session = new AgentSession();
        session.setId(UUID.randomUUID().toString());
        session.setOrganization(user.getOrganization());
        session.setUser(user);
        session.setProject(project);
        session.setScript(script);
        session.setProfileVersion("v1");
        sessions.save(session);

        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID().toString());
        run.setSession(session);
        run.setAgentId("general");
        run.setTraceId(UUID.randomUUID().toString());
        run.setRequestId(requestId);
        run.setRequestHash(hash(requestPayload == null ? "" : requestPayload));
        run.setRequestMessage(requestPayload == null ? "" : requestPayload);
        run.setSourceRevision(script.getContentRevision());
        run.setStatus(AgentRunStatus.QUEUED);
        runs.save(run);

        AgentExecutionClaims claims = new AgentExecutionClaims(organizationId, user.getId(), projectId, screenplayId,
                run.getId(), session.getId(), Set.of("screenplay:read", "draft:create"));
        return new PreparedAgentRun(session.getId(), run.getId(), tokens.issue(claims));
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record PreparedAgentRun(String sessionId, String applicationRunId, String executionToken) { }
}
