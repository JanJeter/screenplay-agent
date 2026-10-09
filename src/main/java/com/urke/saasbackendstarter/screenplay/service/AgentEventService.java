package com.urke.saasbackendstarter.screenplay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.screenplay.domain.AgentEvent;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentEventRepository;
import com.urke.saasbackendstarter.screenplay.repository.AgentRunRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AgentEventService {
    private static final Set<String> PUBLIC_GATEWAY_FAILURE_CODES = Set.of(
            "budget_exhausted", "business_run_limit", "business_run_duplicate",
            "budget_context_limit", "budget_uncertain", "storyboard_source_quote_invalid",
            "storyboard_result_invalid", "storyboard_save_failed", "invalid_structured_output");
    private final AgentEventRepository events;
    private final AgentRunRepository runs;
    private final AgentDraftRepository drafts;
    private final AgentSessionLeaseService leases;
    private final AgentTranscriptService transcript;
    private final ObjectMapper json;
    private final EntityManager entityManager;

    @Transactional
    public PersistedEvent record(String runId, long sequence, String type, JsonNode payload) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "event:" + runId + ":" + sequence).getSingleResult();
        if (events.findByRunIdAndSequence(runId, sequence).isPresent()) return null;
        AgentRun run = runs.findLockedById(runId).orElseThrow(() -> new IllegalArgumentException("Agent run not found"));
        return persist(run, sequence, type, payload);
    }

    /**
     * Makes Java's state machine authoritative for terminal events. The run
     * state, persisted terminal event and final assistant transcript share one
     * transaction, so browsers cannot observe a completed run that Java later
     * reclassifies as failed because its required draft is absent.
     */
    @Transactional
    public PersistedEvent recordTerminal(String runId, long sequence, String gatewayType, JsonNode gatewayPayload) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "event:" + runId + ":" + sequence).getSingleResult();
        if (events.findByRunIdAndSequence(runId, sequence).isPresent()) return null;
        AgentRun run = runs.findLockedById(runId).orElseThrow(() -> new IllegalArgumentException("Agent run not found"));

        AgentRunStatus status = run.getStatus();
        String errorCode = run.getErrorCode();
        if (!terminal(status)) {
            if ("run.cancelled".equals(gatewayType) || status == AgentRunStatus.CANCELLING) {
                status = AgentRunStatus.CANCELLED;
                errorCode = null;
            } else if ("run.completed".equals(gatewayType)
                    && (!run.getSession().getTaskType().requiresDraft() || drafts.existsByRunId(runId))) {
                status = AgentRunStatus.COMPLETED;
                errorCode = null;
            } else {
                status = AgentRunStatus.FAILED;
                errorCode = "run.completed".equals(gatewayType) ? "draft_missing" : gatewayFailureCode(gatewayPayload);
            }
            run.setStatus(status);
            run.setErrorCode(errorCode);
            run.setEndedAt(Instant.now());
            leases.release(run.getSession().getId(), run.getId());
        }

        String type = status == AgentRunStatus.COMPLETED ? "run.completed"
                : status == AgentRunStatus.CANCELLED ? "run.cancelled" : "run.failed";
        JsonNode payload = gatewayPayload == null ? json.createObjectNode() : gatewayPayload.deepCopy();
        if (payload.isObject() && errorCode != null) ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("errorCode", errorCode);
        PersistedEvent persisted = persist(run, sequence, type, payload);
        String assistant = assistantText(runId);
        if (!assistant.isBlank()) transcript.append(run, "assistant", assistant);
        return persisted;
    }

    private PersistedEvent persist(AgentRun run, long sequence, String type, JsonNode payload) {
        AgentEvent event = new AgentEvent();
        event.setId(UUID.randomUUID().toString());
        event.setRun(run);
        event.setSequence(sequence);
        event.setType(type);
        try { event.setPayload(json.writeValueAsString(payload)); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Invalid Gateway event", ex); }
        events.save(event);
        return new PersistedEvent(sequence, type, payload);
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

    private String gatewayFailureCode(JsonNode payload) {
        // Persist only public codes, never arbitrary provider messages. The same
        // reason must survive SSE reconnects and a browser's later GET /runs.
        String code = payload == null ? "" : payload.path("data").path("code").asText();
        return PUBLIC_GATEWAY_FAILURE_CODES.contains(code) ? code : "gateway_failed";
    }

    private boolean terminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED || status == AgentRunStatus.INTERRUPTED;
    }

    public record PersistedEvent(long sequence, String type, JsonNode payload) { }
}
