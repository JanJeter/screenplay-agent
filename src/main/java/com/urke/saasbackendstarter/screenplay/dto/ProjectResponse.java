package com.urke.saasbackendstarter.screenplay.dto;

import com.urke.saasbackendstarter.screenplay.domain.ProjectStatus;
import java.time.Instant;

public record ProjectResponse(Long id, Long organizationId, String name, String description,
                              String genre, ProjectStatus status, Long createdBy,
                              Instant createdAt, Instant updatedAt) {}

