package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.repository.UserRepository;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionClaims;
import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentAuthorizationService {
    private final UserRepository users;
    private final ScriptVersionRepository scripts;
    private final AgentRunRepository runs;

    public AuthorizedAgentScope require(AgentExecutionClaims claims, Long projectId, Long screenplayId, String scope) {
        if (!claims.permits(scope) || !claims.projectId().equals(projectId) || !claims.screenplayId().equals(screenplayId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent scope does not permit this resource");
        }
        User user = users.findByIdAndDeletedFalse(claims.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent user is not active"));
        if (user.getOrganization() == null || !claims.organizationId().equals(user.getOrganization().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent organization mismatch");
        }
        ScriptVersion script = scripts.findByIdAndProjectOrganizationId(screenplayId, claims.organizationId())
                .filter(candidate -> candidate.getProject().getId().equals(projectId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Screenplay not found"));
        AgentRun run = runs.findById(claims.applicationRunId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Agent run not found"));
        AgentSession session = run.getSession();
        if (!session.getId().equals(claims.sessionId()) || !session.getOrganization().getId().equals(claims.organizationId())
                || !session.getUser().getId().equals(claims.userId()) || !session.getProject().getId().equals(projectId)
                || !session.getScript().getId().equals(screenplayId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent execution context mismatch");
        }
        if (run.getStatus() == AgentRunStatus.CANCELLED || run.getStatus() == AgentRunStatus.FAILED
                || run.getStatus() == AgentRunStatus.INTERRUPTED || run.getStatus() == AgentRunStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent run is no longer active");
        }
        return new AuthorizedAgentScope(script, run);
    }

    public record AuthorizedAgentScope(ScriptVersion script, AgentRun run) { }
}
