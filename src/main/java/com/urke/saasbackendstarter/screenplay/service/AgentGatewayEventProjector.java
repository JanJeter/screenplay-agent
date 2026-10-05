package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.agent.AgentGatewayClient;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentGatewayEventProjector {
    private final AgentEventService events;
    private final AgentRunWorker worker;
    private final AgentTranscriptService transcript;
    private final AgentRunRepository runs;

    public void project(String runId, AgentGatewayClient.GatewayEvent event) {
        long sequence;
        try { sequence = Long.parseLong(event.id()); }
        catch (NumberFormatException | NullPointerException ex) { return; }
        if (!events.record(runId, sequence, event.type(), event.data())) return;
        if (event.type().startsWith("run.") && !"run.started".equals(event.type())) {
            worker.terminal(runId, event.type());
            String assistant = events.assistantText(runId);
            if (!assistant.isBlank()) {
                var run = runs.findById(runId).orElseThrow();
                transcript.append(run, "assistant", assistant);
            }
        }
    }
}
