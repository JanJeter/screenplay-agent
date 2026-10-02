package com.urke.saasbackendstarter.screenplay.agent;

import java.util.List;

public record ScriptAnalysisResult(List<ParsedScene> scenes) {
    public ScriptAnalysisResult {
        scenes = List.copyOf(scenes);
    }
}
