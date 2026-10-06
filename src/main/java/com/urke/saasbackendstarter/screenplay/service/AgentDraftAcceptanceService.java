package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.screenplay.domain.AgentDraft;
import com.urke.saasbackendstarter.screenplay.domain.AgentDraftStatus;
import com.urke.saasbackendstarter.screenplay.domain.ScriptStatus;
import com.urke.saasbackendstarter.screenplay.domain.ScriptVersion;
import com.urke.saasbackendstarter.screenplay.domain.AgentTaskType;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentDraftSummary;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScreenplayProjectRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptSceneRepository;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentDraftAcceptanceService {
    private final CurrentUserProvider currentUser;
    private final AgentDraftRepository drafts;
    private final ScreenplayProjectRepository projects;
    private final ScriptVersionRepository scripts;
    private final ScriptSceneRepository scenes;
    private final ObjectMapper json;

    public List<AgentDraftSummary> list(Long projectId) {
        User user = currentUser.getCurrentUser();
        Long orgId = organizationId(user);
        if (!projects.existsById(projectId) || projects.findByIdAndOrganizationId(projectId, orgId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
        return drafts.findAllBySourceScriptProjectIdAndOrganizationIdOrderByCreatedAtDesc(projectId, orgId)
                .stream().map(this::summary).toList();
    }

    @Transactional
    public AgentDraftSummary accept(String draftId) {
        AgentDraft draft = owned(draftId);
        if (draft.getStatus() == AgentDraftStatus.ACCEPTED) return summary(draft);
        if (draft.getStatus() != AgentDraftStatus.PENDING_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft cannot be accepted");
        }
        if (draft.getSourceRevision() != draft.getSourceScript().getContentRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft source revision is no longer current");
        }
        if (draft.getRun().getSession().getTaskType() == AgentTaskType.REWRITE_DIALOGUE) {
            draft.setAcceptedScript(applyDialogueRewrite(draft));
        }
        draft.setStatus(AgentDraftStatus.ACCEPTED);
        return summary(draft);
    }

    @Transactional
    public AgentDraftSummary reject(String draftId) {
        AgentDraft draft = owned(draftId);
        if (draft.getStatus() == AgentDraftStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Accepted draft cannot be rejected");
        }
        draft.setStatus(AgentDraftStatus.REJECTED);
        return summary(draft);
    }

    private AgentDraft owned(String id) {
        Long orgId = organizationId(currentUser.getCurrentUser());
        return drafts.findLockedByIdAndOrganizationId(id, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent draft not found"));
    }

    private Long organizationId(User user) {
        if (user.getOrganization() == null || user.getOrganization().getId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization membership is required");
        }
        return user.getOrganization().getId();
    }

    private AgentDraftSummary summary(AgentDraft draft) {
        return new AgentDraftSummary(draft.getId(), draft.getRun().getId(), Long.toString(draft.getSourceScript().getId()),
                draft.getSourceRevision(), draft.getContent(), draft.getStatus().name().toLowerCase(),
                draft.getAcceptedScript() == null ? null : Long.toString(draft.getAcceptedScript().getId()),
                artifactType(draft), draft.getTargetSceneId(), draft.getCreatedAt());
    }

    private ScriptVersion applyDialogueRewrite(AgentDraft draft) {
        if (draft.getTargetSceneId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dialogue rewrite is missing its target scene");
        }
        long sceneId;
        try { sceneId = Long.parseLong(draft.getTargetSceneId()); }
        catch (NumberFormatException ex) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Dialogue rewrite target is invalid"); }
        var scene = scenes.findByIdAndScriptVersionId(sceneId, draft.getSourceScript().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Dialogue rewrite target no longer exists"));
        String original = normalizeLineEndings(draft.getSourceScript().getRawText());
        String sceneText = normalizeLineEndings(scene.getRawText());
        ScenePosition position = scenePosition(draft.getSourceScript().getId(), scene.getId());
        int location = position.offset();
        if (location < 0 || location + sceneText.length() > original.length()
                || !original.regionMatches(location, sceneText, 0, sceneText.length())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Dialogue rewrite target no longer matches source text");
        }
        String rewrittenScene = applyDialoguePatch(sceneText, draft.getContent(), position.hasFollowingScene());
        String merged = original.substring(0, location) + rewrittenScene
                + original.substring(location + sceneText.length());
        ScriptVersion accepted = new ScriptVersion();
        accepted.setProject(draft.getSourceScript().getProject());
        accepted.setVersionName("Agent dialogue rewrite " + draft.getId().substring(0, 8));
        accepted.setRawText(merged);
        accepted.setStatus(ScriptStatus.UPLOADED);
        accepted.setContentRevision(1L);
        return scripts.save(accepted);
    }

    /**
     * Scene ids are authoritative, including when two scenes have equal text.
     * Script scenes are parser partitions, so their ordered raw text defines the
     * precise source interval in the version rather than relying on indexOf().
     */
    private ScenePosition scenePosition(Long scriptId, Long targetSceneId) {
        int offset = 0;
        var ordered = scenes.findAllByScriptVersionIdOrderBySortOrderAsc(scriptId);
        for (int index = 0; index < ordered.size(); index++) {
            var candidate = ordered.get(index);
            if (candidate.getId().equals(targetSceneId)) return new ScenePosition(offset, index + 1 < ordered.size());
            offset += normalizeLineEndings(candidate.getRawText()).length();
        }
        return new ScenePosition(-1, false);
    }

    /**
     * A dialogue draft is an in-scene patch, not a replacement scene. Its
     * offset and source text make the application unambiguous and prevent a
     * stale model response from replacing an unrelated duplicate line.
     */
    private String applyDialoguePatch(String sceneText, String content, boolean hasFollowingScene) {
        try {
            JsonNode patch = json.readTree(content);
            String source = normalizeLineEndings(patch.path("original").asText(null));
            String proposed = normalizeLineEndings(patch.path("proposed").asText(null));
            JsonNode offset = patch.get("sourceOffset");
            if (source == null || source.isBlank() || proposed == null || proposed.isBlank()
                    || offset == null || !offset.canConvertToInt()) {
                throw new IllegalArgumentException();
            }
            int start = offset.intValue();
            if (start < 0 || start + source.length() > sceneText.length()
                    || !sceneText.regionMatches(start, source, 0, source.length())) {
                throw new IllegalArgumentException();
            }
            // Parser partitions include the line break(s) immediately before the
            // next scene heading. A dialogue patch must not consume that boundary.
            if (hasFollowingScene && start + source.length() > contentEnd(sceneText)) {
                throw new IllegalArgumentException();
            }
            return sceneText.substring(0, start) + proposed + sceneText.substring(start + source.length());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Dialogue rewrite must contain a valid in-scene patch");
        }
    }

    private int contentEnd(String sceneText) {
        int index = sceneText.length();
        while (index > 0 && sceneText.charAt(index - 1) == '\n') index--;
        return index;
    }

    private String normalizeLineEndings(String value) {
        return value == null ? null : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private record ScenePosition(int offset, boolean hasFollowingScene) { }

    private String artifactType(AgentDraft draft) {
        return switch (draft.getRun().getSession().getTaskType()) {
            case REWRITE_DIALOGUE -> "scene_rewrite";
            case EXTRACT_CHARACTERS -> "character_proposal";
            case BUILD_OUTLINE -> "outline";
            // Storyboard task types persist their own artifacts and cannot create AgentDrafts.
            case ANALYZE_SCENE, CHECK_PLOT_LOGIC, GENERAL, GENERATE_STORYBOARD, REWRITE_STORYBOARD_SHOT -> "report";
        };
    }
}
