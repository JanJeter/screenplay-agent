package com.urke.saasbackendstarter.screenplay.dto.agent;

import jakarta.validation.constraints.NotNull;

public record CreateAgentSessionRequest(@NotNull Long screenplayId, String taskType) {
}
