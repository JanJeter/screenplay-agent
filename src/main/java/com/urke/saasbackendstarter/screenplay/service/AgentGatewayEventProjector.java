package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.screenplay.agent.AgentGatewayClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentGatewayEventProjector {
    private final AgentEventService events;
    private final AgentEventBroadcaster broadcaster;

    public void project(String runId, AgentGatewayClient.GatewayEvent event) {
        long sequence;
        try { sequence = Long.parseLong(event.id()); }
        catch (NumberFormatException | NullPointerException ex) { return; }
        AgentEventService.PersistedEvent persisted = event.type().startsWith("run.") && !"run.started".equals(event.type())
                ? events.recordTerminal(runId, sequence, event.type(), event.data())
                : events.record(runId, sequence, event.type(), event.data());
        if (persisted != null) broadcaster.broadcast(runId, persisted);
    }
}
