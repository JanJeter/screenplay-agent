package com.urke.saasbackendstarter.screenplay.dto;

import com.urke.saasbackendstarter.screenplay.domain.ScriptStatus;
import java.time.Instant;

public record ScriptResponse(Long id, Long projectId, String versionName, String originalFilename,
                             String rawText, ScriptStatus status, Instant createdAt) {}

