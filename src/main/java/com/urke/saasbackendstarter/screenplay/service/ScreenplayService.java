package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.screenplay.domain.*;
import com.urke.saasbackendstarter.screenplay.dto.*;
import com.urke.saasbackendstarter.screenplay.export.MarkdownExporter;
import com.urke.saasbackendstarter.screenplay.mapper.ScreenplayMapper;
import com.urke.saasbackendstarter.screenplay.repository.*;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ScreenplayService {
    private final CurrentUserProvider currentUserProvider;
    private final ScreenplayProjectRepository projects;
    private final ScriptVersionRepository scripts;
    private final ScriptSceneRepository scenes;
    private final SceneElementRepository elements;
    private final AnalysisLock analysisLock;
    private final AnalysisTransactions analysisTransactions;
    private final MarkdownExporter markdownExporter;

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        User user = currentUserProvider.getCurrentUser();
        requireOrganization(user);
        ScreenplayProject project = new ScreenplayProject();
        project.setOrganization(user.getOrganization());
        project.setCreatedBy(user);
        project.setName(request.name());
        project.setDescription(request.description());
        project.setGenre(request.genre());
        project.setStatus(ProjectStatus.ACTIVE);
        return ScreenplayMapper.project(projects.save(project));
    }

    public List<ProjectResponse> listProjects() {
        return projects.findAllByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId())
                .stream().map(ScreenplayMapper::project).toList();
    }

    public ProjectResponse getProject(Long projectId) {
        return ScreenplayMapper.project(requireProject(projectId, organizationId()));
    }

    @Transactional
    public ScriptResponse createScript(Long projectId, CreateScriptRequest request) {
        ScreenplayProject project = requireProject(projectId, organizationId());
        // Enforce the raw input limit before newline normalization or any parser invocation.
        if (request.rawText() == null || request.rawText().isBlank() || request.rawText().length() > 500_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rawText must contain 1 to 500000 characters and must not be blank");
        }
        ScriptVersion script = new ScriptVersion();
        script.setProject(project);
        script.setVersionName(request.versionName());
        script.setOriginalFilename(request.originalFilename());
        script.setRawText(request.rawText().replace("\r\n", "\n").replace('\r', '\n'));
        script.setStatus(ScriptStatus.UPLOADED);
        return ScreenplayMapper.script(scripts.save(script));
    }

    public List<ScriptSummary> listScripts(Long projectId) {
        Long orgId = organizationId();
        requireProject(projectId, orgId);
        return scripts.findAllByProjectIdAndProjectOrganizationIdOrderByCreatedAtDescIdDesc(projectId, orgId)
                .stream().map(ScreenplayMapper::summary).toList();
    }

    public ScriptResponse getScript(Long scriptId) {
        return ScreenplayMapper.script(requireScript(scriptId, organizationId()));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AnalysisResponse analyze(Long scriptId) {
        Long orgId = organizationId();
        requireScript(scriptId, orgId);
        return analysisLock.withLock(scriptId, () -> {
            try {
                return analysisTransactions.replace(scriptId, orgId);
            } catch (RuntimeException failure) {
                // The replacement transaction has already rolled back when this separate bean returns.
                try {
                    analysisTransactions.markFailed(scriptId, orgId);
                } catch (RuntimeException statusFailure) {
                    failure.addSuppressed(statusFailure);
                }
                log.error("Analysis failed for script {}", scriptId, failure);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Script analysis failed; previous results have been retained", failure);
            }
        });
    }

    public List<SceneResponse> listScenes(Long scriptId) {
        requireScript(scriptId, organizationId());
        return sceneResponses(scriptId);
    }

    public List<ElementResponse> listElements(Long scriptId) {
        requireScript(scriptId, organizationId());
        return elementResponses(scriptId);
    }

    // A stable snapshot prevents combining old scenes with newly committed elements during reanalysis.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String exportMarkdown(Long scriptId) {
        ScriptVersion script = requireScript(scriptId, organizationId());
        return markdownExporter.export(script.getProject().getName(), ScreenplayMapper.script(script),
                sceneResponses(scriptId), elementResponses(scriptId));
    }

    private List<SceneResponse> sceneResponses(Long scriptId) {
        return scenes.findAllByScriptVersionIdOrderBySortOrderAsc(scriptId)
                .stream().map(ScreenplayMapper::scene).toList();
    }

    private List<ElementResponse> elementResponses(Long scriptId) {
        return elements.findAllBySceneScriptVersionIdOrderBySceneSortOrderAscIdAsc(scriptId)
                .stream().map(ScreenplayMapper::element).toList();
    }

    private Long organizationId() {
        return requireOrganization(currentUserProvider.getCurrentUser());
    }

    private Long requireOrganization(User user) {
        if (user.getOrganization() == null || user.getOrganization().getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization membership is required");
        }
        return user.getOrganization().getId();
    }

    private ScreenplayProject requireProject(Long id, Long orgId) {
        return projects.findByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    private ScriptVersion requireScript(Long id, Long orgId) {
        return scripts.findByIdAndProjectOrganizationId(id, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Script not found"));
    }
}

