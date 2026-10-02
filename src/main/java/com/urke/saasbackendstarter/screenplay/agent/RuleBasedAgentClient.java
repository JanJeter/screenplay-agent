package com.urke.saasbackendstarter.screenplay.agent;

import com.urke.saasbackendstarter.screenplay.parser.RuleBasedScriptParser;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedAgentClient implements AgentClient {
    private final RuleBasedScriptParser parser;

    public RuleBasedAgentClient(RuleBasedScriptParser parser) {
        this.parser = parser;
    }

    @Override
    public ScriptAnalysisResult analyzeScript(String scriptText) {
        return parser.parse(scriptText);
    }
}
