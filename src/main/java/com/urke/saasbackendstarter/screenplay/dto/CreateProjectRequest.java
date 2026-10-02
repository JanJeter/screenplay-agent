package com.urke.saasbackendstarter.screenplay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 10000) String description,
        @Size(max = 120) String genre) {
    public CreateProjectRequest {
        name = name == null ? null : name.strip();
    }
}

