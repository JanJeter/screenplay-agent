package com.urke.saasbackendstarter.screenplay.dto;

import com.urke.saasbackendstarter.screenplay.domain.ScriptStatus;
import java.time.Instant;

public record ScriptSummary(Long id, Long projectId, String versionName, String originalFilename,
                            ScriptStatus status, Instant createdAt) {}

