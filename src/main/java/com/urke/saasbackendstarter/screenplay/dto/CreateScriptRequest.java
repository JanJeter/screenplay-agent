package com.urke.saasbackendstarter.screenplay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateScriptRequest(
        @NotBlank @Size(max = 80) String versionName,
        @Size(max = 255) String originalFilename,
        @NotBlank @Size(max = 500_000) String rawText) {
    public CreateScriptRequest {
        versionName = versionName == null ? null : versionName.strip();
    }
}

