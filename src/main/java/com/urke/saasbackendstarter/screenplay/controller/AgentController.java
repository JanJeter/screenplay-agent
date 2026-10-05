package com.urke.saasbackendstarter.screenplay.controller;

import com.urke.saasbackendstarter.screenplay.dto.agent.*;
import com.urke.saasbackendstarter.screenplay.service.AgentDraftAcceptanceService;
import com.urke.saasbackendstarter.screenplay.service.AgentRunService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/screenplay")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AgentController {
    private final AgentRunService runs;
    private final AgentDraftAcceptanceService drafts;

    @PostMapping("/projects/{projectId}/agent/sessions")
    public ResponseEntity<AgentSessionResponse> createSession(@PathVariable Long projectId,
                                                               @Valid @RequestBody CreateAgentSessionRequest request) {
        AgentSessionResponse response = runs.createSession(projectId, request);
        return ResponseEntity.created(URI.create("/api/v1/screenplay/agent/sessions/" + response.id())).body(response);
    }

    @PostMapping("/agent/sessions/{sessionId}/messages")
    public ResponseEntity<AgentRunResponse> submit(@PathVariable String sessionId,
                                                   @Valid @RequestBody CreateAgentMessageRequest request) {
        return ResponseEntity.accepted().body(runs.submit(sessionId, request));
    }

    @PostMapping("/agent/runs/{runId}/subtasks")
    public ResponseEntity<AgentRunResponse> createSubtask(@PathVariable String runId,
                                                           @Valid @RequestBody CreateAgentSubtaskRequest request) {
        return ResponseEntity.accepted().body(runs.createSubtask(runId, request));
    }

    @GetMapping("/agent/runs/{runId}")
    public AgentRunResponse getRun(@PathVariable String runId) {
        return runs.getRun(runId);
    }

    @GetMapping(value = "/agent/runs/{runId}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return runs.events(runId, lastEventId);
    }

    @PostMapping("/agent/runs/{runId}/cancel")
    public ResponseEntity<AgentRunResponse> cancel(@PathVariable String runId) {
        return ResponseEntity.accepted().body(runs.cancel(runId));
    }

    @GetMapping("/projects/{projectId}/agent/drafts")
    public List<AgentDraftSummary> listDrafts(@PathVariable Long projectId) {
        return drafts.list(projectId);
    }

    @PostMapping("/agent/drafts/{draftId}/accept")
    public AgentDraftSummary accept(@PathVariable String draftId) {
        return drafts.accept(draftId);
    }

    @PostMapping("/agent/drafts/{draftId}/reject")
    public AgentDraftSummary reject(@PathVariable String draftId) {
        return drafts.reject(draftId);
    }
}
