package com.urke.saasbackendstarter.screenplay.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAgentMessageRequest(
        @NotBlank @Size(max = 12_000) String message,
        @NotBlank @Size(max = 128) String clientRequestId
) {
}
