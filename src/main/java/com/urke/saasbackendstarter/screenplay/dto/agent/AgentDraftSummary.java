package com.urke.saasbackendstarter.screenplay.dto.agent;

import java.time.Instant;

public record AgentDraftSummary(String id, String runId, String screenplayId, long sourceVersion,
                                String content, String status, String acceptedScriptId,
                                String artifactType, String targetSceneId, Instant createdAt) {
}
