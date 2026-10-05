package com.urke.saasbackendstarter.screenplay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.screenplay.domain.AgentEvent;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.repository.AgentEventRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentEventService {
    private final AgentEventRepository events;
    private final AgentRunRepository runs;
    private final ObjectMapper json;
    private final EntityManager entityManager;

    @Transactional
    public boolean record(String runId, long sequence, String type, JsonNode payload) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "event:" + runId + ":" + sequence).getSingleResult();
        if (events.findByRunIdAndSequence(runId, sequence).isPresent()) return false;
        AgentRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("Agent run not found"));
        AgentEvent event = new AgentEvent();
        event.setId(UUID.randomUUID().toString());
        event.setRun(run);
        event.setSequence(sequence);
        event.setType(type);
        try { event.setPayload(json.writeValueAsString(payload)); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Invalid Gateway event", ex); }
        events.save(event);
        return true;
    }

    @Transactional(readOnly = true)
    public List<PersistedEvent> after(String runId, long after) {
        return events.findAllByRunIdAndSequenceGreaterThanOrderBySequenceAsc(runId, after).stream()
                .map(event -> new PersistedEvent(event.getSequence(), event.getType(), parse(event.getPayload()))).toList();
    }

    @Transactional(readOnly = true)
    public String assistantText(String runId) {
        StringBuilder text = new StringBuilder();
        for (AgentEvent event : events.findAllByRunIdOrderBySequenceAsc(runId)) {
            if ("text.delta".equals(event.getType())) text.append(parse(event.getPayload()).path("data").path("delta").asText());
        }
        return text.toString();
    }

    private JsonNode parse(String payload) {
        try { return json.readTree(payload); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Stored Agent event is invalid", ex); }
    }

    public record PersistedEvent(long sequence, String type, JsonNode payload) { }
}
