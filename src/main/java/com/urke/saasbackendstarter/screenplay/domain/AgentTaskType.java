package com.urke.saasbackendstarter.screenplay.domain;

public enum AgentTaskType {
    GENERAL,
    ANALYZE_SCENE,
    REWRITE_DIALOGUE,
    EXTRACT_CHARACTERS,
    BUILD_OUTLINE,
    CHECK_PLOT_LOGIC,
    GENERATE_STORYBOARD,
    REWRITE_STORYBOARD_SHOT;

    public static AgentTaskType fromApi(String value) {
        if (value == null || value.isBlank()) return GENERAL;
        try { return valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Unsupported taskType"); }
    }

    public boolean requiresDraft() {
        return this == ANALYZE_SCENE || this == REWRITE_DIALOGUE || this == EXTRACT_CHARACTERS || this == BUILD_OUTLINE;
    }

    public boolean requiresStoryboardArtifact() { return this == GENERATE_STORYBOARD || this == REWRITE_STORYBOARD_SHOT; }
}
