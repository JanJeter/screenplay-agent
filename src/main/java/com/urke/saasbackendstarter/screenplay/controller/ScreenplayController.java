package com.urke.saasbackendstarter.screenplay.controller;

import com.urke.saasbackendstarter.screenplay.dto.*;
import com.urke.saasbackendstarter.screenplay.service.ScreenplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/screenplay")
@RequiredArgsConstructor
@Tag(name = "Screenplay", description = "Organization-scoped screenplay projects and synchronous rule-based analysis")
@SecurityRequirement(name = "bearerAuth")
public class ScreenplayController {
    private final ScreenplayService service;

    @PostMapping("/projects")
    @Operation(summary = "Create a screenplay project in the current organization",
            responses = {@ApiResponse(responseCode = "201", description = "Project created"),
                    @ApiResponse(responseCode = "400", description = "Invalid project metadata")})
    public ResponseEntity<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse project = service.createProject(request);
        return ResponseEntity.created(URI.create("/api/v1/screenplay/projects/" + project.id())).body(project);
    }

    @GetMapping("/projects")
    @Operation(summary = "List screenplay projects in the current organization")
    public List<ProjectResponse> listProjects() {
        return service.listProjects();
    }

    @GetMapping("/projects/{projectId}")
    @Operation(summary = "Get a screenplay project", responses = {
            @ApiResponse(responseCode = "200", description = "Project details"),
            @ApiResponse(responseCode = "404", description = "Project absent or belongs to another organization")})
    public ProjectResponse getProject(@PathVariable Long projectId) {
        return service.getProject(projectId);
    }

    @PostMapping("/projects/{projectId}/scripts")
    @Operation(summary = "Save a script version as raw text",
            description = "JSON input; maximum 500000 characters, nonblank. Line endings are normalized to LF.",
            responses = {@ApiResponse(responseCode = "201", description = "Script version saved"),
                    @ApiResponse(responseCode = "400", description = "Invalid script text or version metadata"),
                    @ApiResponse(responseCode = "404", description = "Project not found")})
    public ResponseEntity<ScriptResponse> createScript(@PathVariable Long projectId,
                                                       @Valid @RequestBody CreateScriptRequest request) {
        ScriptResponse script = service.createScript(projectId, request);
        return ResponseEntity.created(URI.create("/api/v1/screenplay/scripts/" + script.id())).body(script);
    }

    @GetMapping("/projects/{projectId}/scripts")
    @Operation(summary = "List a project's script version history")
    public List<ScriptSummary> listScripts(@PathVariable Long projectId) {
        return service.listScripts(projectId);
    }

    @GetMapping("/scripts/{scriptId}")
    @Operation(summary = "Get a script version including raw text")
    public ScriptResponse getScript(@PathVariable Long scriptId) {
        return service.getScript(scriptId);
    }

    @PostMapping("/scripts/{scriptId}/analyze")
    @Operation(summary = "Analyze a script synchronously",
            description = "Atomically replaces scenes and elements. On failure, previous results remain readable and exportable.",
            responses = {@ApiResponse(responseCode = "200", description = "Analysis completed"),
                    @ApiResponse(responseCode = "404", description = "Script not found"),
                    @ApiResponse(responseCode = "409", description = "An analysis for this script is already running"),
                    @ApiResponse(responseCode = "503", description = "Analysis capacity is busy; retry later"),
                    @ApiResponse(responseCode = "500", description = "Analysis failed; previous results retained")})
    public AnalysisResponse analyze(@PathVariable Long scriptId) {
        return service.analyze(scriptId);
    }

    @GetMapping("/scripts/{scriptId}/scenes")
    @Operation(summary = "List parsed scenes in script order")
    public List<SceneResponse> listScenes(@PathVariable Long scriptId) {
        return service.listScenes(scriptId);
    }

    @GetMapping("/scripts/{scriptId}/elements")
    @Operation(summary = "List extracted characters, locations, props, sounds and risks")
    public List<ElementResponse> listElements(@PathVariable Long scriptId) {
        return service.listElements(scriptId);
    }

    @GetMapping(value = "/scripts/{scriptId}/export/markdown", produces = "text/markdown;charset=UTF-8")
    @Operation(summary = "Export the stored scene breakdown as Markdown",
            responses = @ApiResponse(responseCode = "200", description = "UTF-8 Markdown text",
                    content = @Content(mediaType = "text/markdown", schema = @Schema(type = "string"))))
    public String exportMarkdown(@PathVariable Long scriptId) {
        return service.exportMarkdown(scriptId);
    }
}

