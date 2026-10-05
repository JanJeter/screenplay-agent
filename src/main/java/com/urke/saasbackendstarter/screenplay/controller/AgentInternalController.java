package com.urke.saasbackendstarter.screenplay.controller;

import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionClaims;
import com.urke.saasbackendstarter.screenplay.agent.security.AgentExecutionTokenService;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentDraftResponse;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentScreenplayResponse;
import com.urke.saasbackendstarter.screenplay.dto.agent.SaveAgentDraftRequest;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentSceneResponse;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentSceneSummaryResponse;
import com.urke.saasbackendstarter.screenplay.dto.agent.CreateAgentSubtaskRequest;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentRunResponse;
import com.urke.saasbackendstarter.screenplay.repository.ScriptSceneRepository;
import com.urke.saasbackendstarter.screenplay.service.AgentAuthorizationService;
import com.urke.saasbackendstarter.screenplay.service.AgentDraftService;
import com.urke.saasbackendstarter.screenplay.service.AgentRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Internal, capability-authenticated adapters for the Node Agent Gateway.
 * These endpoints deliberately do not use CurrentUserProvider or browser JWTs.
 */
@RestController
@RequestMapping("/internal/agent/projects/{projectId}")
@RequiredArgsConstructor
public class AgentInternalController {
    private final AgentExecutionTokenService tokens;
    private final AgentAuthorizationService authorization;
    private final AgentDraftService drafts;
    private final ScriptSceneRepository scenes;
    private final AgentRunService runs;

    @GetMapping("/screenplays/{screenplayId}")
    public AgentScreenplayResponse getScreenplay(@RequestHeader("Authorization") String bearer,
                                                 @PathVariable Long projectId, @PathVariable Long screenplayId) {
        AgentExecutionClaims claims = tokens.verifyBearer(bearer);
        var authorized = authorization.require(claims, projectId, screenplayId, "screenplay:read");
        var script = authorized.script();
        if (script.getRawText().length() > 12_000) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "Screenplay is too large; retrieve scenes instead");
        }
        String title = script.getVersionName();
        if (title.length() > 200) title = title.substring(0, 200);
        return new AgentScreenplayResponse(Long.toString(script.getId()), Long.toString(projectId), title,
                script.getRawText(), script.getContentRevision());
    }

    @GetMapping("/screenplays/{screenplayId}/scenes")
    public List<AgentSceneSummaryResponse> listScenes(@RequestHeader("Authorization") String bearer,
                                                       @PathVariable Long projectId, @PathVariable Long screenplayId) {
        AgentExecutionClaims claims = tokens.verifyBearer(bearer);
        authorization.require(claims, projectId, screenplayId, "screenplay:read");
        return scenes.findAllByScriptVersionIdOrderBySortOrderAsc(screenplayId).stream().limit(200)
                .map(scene -> new AgentSceneSummaryResponse(Long.toString(scene.getId()), scene.getSceneNo(),
                        scene.getHeading(), scene.getSortOrder())).toList();
    }

    @GetMapping("/screenplays/{screenplayId}/scenes/{sceneId}")
    public AgentSceneResponse getScene(@RequestHeader("Authorization") String bearer,
                                       @PathVariable Long projectId, @PathVariable Long screenplayId,
                                       @PathVariable Long sceneId) {
        AgentExecutionClaims claims = tokens.verifyBearer(bearer);
        var authorized = authorization.require(claims, projectId, screenplayId, "screenplay:read");
        var scene = scenes.findByIdAndScriptVersionId(sceneId, screenplayId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Scene not found"));
        if (scene.getRawText().length() > 12_000) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE, "Scene is too large; chunked retrieval is required");
        }
        return new AgentSceneResponse(Long.toString(screenplayId), Long.toString(projectId), Long.toString(sceneId),
                scene.getHeading(), scene.getRawText(), authorized.script().getContentRevision());
    }

    @PostMapping("/drafts")
    public AgentDraftResponse saveDraft(@RequestHeader("Authorization") String bearer,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @PathVariable Long projectId,
                                        @Valid @RequestBody SaveAgentDraftRequest request) {
        AgentExecutionClaims claims = tokens.verifyBearer(bearer);
        long screenplayId;
        try {
            screenplayId = Long.parseLong(request.screenplayId());
        } catch (NumberFormatException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "screenplayId must be a numeric string");
        }
        var authorized = authorization.require(claims, projectId, screenplayId, "draft:create");
        return drafts.save(authorized, request, idempotencyKey);
    }

    @PostMapping("/runs/{parentRunId}/subtasks")
    public AgentRunResponse createSubtask(@RequestHeader("Authorization") String bearer,
                                          @PathVariable Long projectId, @PathVariable String parentRunId,
                                          @Valid @RequestBody CreateAgentSubtaskRequest request) {
        AgentExecutionClaims claims = tokens.verifyBearer(bearer);
        long screenplayId = claims.screenplayId();
        var authorized = authorization.require(claims, projectId, screenplayId, "task:create");
        if (!authorized.run().getId().equals(parentRunId)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Subtask parent does not match execution context");
        }
        return runs.createSubtaskForExecution(authorized.run(), request);
    }
}
