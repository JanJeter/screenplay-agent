package com.urke.saasbackendstarter.screenplay.agent;

/** Boundary for script analysis; a remote PI client can implement this later. */
public interface AgentClient {
    ScriptAnalysisResult analyzeScript(String scriptText);
}
