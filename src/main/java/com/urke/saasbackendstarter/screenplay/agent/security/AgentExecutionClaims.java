package com.urke.saasbackendstarter.screenplay.agent.security;

import java.util.Set;

/** Immutable scope extracted from a Gateway-to-Java execution credential. */
public record AgentExecutionClaims(
        Long organizationId,
        Long userId,
        Long projectId,
        Long screenplayId,
        String applicationRunId,
        String sessionId,
        Set<String> scopes
) {
    public boolean permits(String scope) {
        return scopes.contains(scope);
    }
}
