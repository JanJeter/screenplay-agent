package com.urke.saasbackendstarter.screenplay.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionTokenServiceTest {
    private final AgentExecutionTokenService tokens = new AgentExecutionTokenService(
            "agent-execution-test-secret-must-have-at-least-32-bytes", 60);

    @Test
    void roundTripsOnlyTheBoundExecutionScope() {
        AgentExecutionClaims original = new AgentExecutionClaims(1L, 2L, 3L, 4L,
                "run-1", "session-1", Set.of("screenplay:read", "draft:create"));

        AgentExecutionClaims parsed = tokens.verifyBearer("Bearer " + tokens.issue(original));

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.permits("draft:create")).isTrue();
        assertThat(parsed.permits("draft:accept")).isFalse();
    }

    @Test
    void rejectsCredentialsSignedByAnotherInternalIssuerKey() {
        AgentExecutionClaims claims = new AgentExecutionClaims(1L, 2L, 3L, 4L,
                "run-1", "session-1", Set.of("screenplay:read"));
        String tokenFromAnotherKey = new AgentExecutionTokenService(
                "different-agent-execution-test-secret-at-least-32-bytes", 60).issue(claims);

        assertThatThrownBy(() -> tokens.verifyBearer("Bearer " + tokenFromAnotherKey))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid agent execution credential");
    }
}
