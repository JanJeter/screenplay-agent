package com.urke.saasbackendstarter.screenplay.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAgentSubtaskRequest(
        @NotBlank @Size(max = 40) String taskType,
        @NotBlank @Size(max = 12_000) String message,
        @NotBlank @Size(max = 128) String clientRequestId
) { }
