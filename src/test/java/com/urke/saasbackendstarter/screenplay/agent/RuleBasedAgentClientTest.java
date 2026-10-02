package com.urke.saasbackendstarter.screenplay.agent;

import com.urke.saasbackendstarter.screenplay.parser.RuleBasedScriptParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedAgentClientTest {
    @Test
    void analyzesThroughAgentBoundary() {
        AgentClient client = new RuleBasedAgentClient(new RuleBasedScriptParser());

        ScriptAnalysisResult result = client.analyzeScript("INT. STUDIO - DAY\nA light shines.");

        assertThat(result.scenes()).hasSize(1);
        assertThat(result.scenes().getFirst().location()).isEqualTo("STUDIO");
    }
}
