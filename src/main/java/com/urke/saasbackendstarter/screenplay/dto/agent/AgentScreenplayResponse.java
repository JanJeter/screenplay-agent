package com.urke.saasbackendstarter.screenplay.dto.agent;

public record AgentScreenplayResponse(String screenplayId, String projectId, String title, String content, long version) {
}
