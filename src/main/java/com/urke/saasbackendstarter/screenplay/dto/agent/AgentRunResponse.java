package com.urke.saasbackendstarter.screenplay.dto.agent;

import java.time.Instant;

public record AgentRunResponse(String id, String sessionId, String parentRunId, String agentId, String traceId, String status, String errorCode,
                               Instant createdAt, Instant startedAt, Instant endedAt, ResultRef resultRef) {
    public record ResultRef(String type, String id, String storyboardId) { }
}
