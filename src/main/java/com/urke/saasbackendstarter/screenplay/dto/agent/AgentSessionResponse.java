package com.urke.saasbackendstarter.screenplay.dto.agent;

public record AgentSessionResponse(String id, String projectId, String screenplayId, String taskType, String status) {
}
