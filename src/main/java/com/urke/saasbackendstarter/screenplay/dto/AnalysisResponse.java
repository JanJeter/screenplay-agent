package com.urke.saasbackendstarter.screenplay.dto;

import com.urke.saasbackendstarter.screenplay.domain.ScriptStatus;

public record AnalysisResponse(Long scriptId, ScriptStatus status, int sceneCount, int elementCount) {}

