package com.urke.saasbackendstarter.screenplay.dto.agent;

public record AgentSceneResponse(String screenplayId, String projectId, String sceneId, String heading,
                                 String content, long version) {
}
