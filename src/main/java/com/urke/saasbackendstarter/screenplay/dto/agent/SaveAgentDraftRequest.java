package com.urke.saasbackendstarter.screenplay.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SaveAgentDraftRequest(
        @NotBlank @Size(max = 128) String screenplayId,
        @NotBlank @Size(max = 128) String sessionId,
        @NotBlank @Size(max = 12_000) String content,
        @NotNull Long sourceVersion,
        @Size(max = 36) String targetSceneId
) {
}
