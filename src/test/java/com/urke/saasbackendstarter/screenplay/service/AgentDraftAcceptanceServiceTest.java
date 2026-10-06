package com.urke.saasbackendstarter.screenplay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.screenplay.domain.AgentDraft;
import com.urke.saasbackendstarter.screenplay.domain.AgentDraftStatus;
import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import com.urke.saasbackendstarter.screenplay.domain.AgentSession;
import com.urke.saasbackendstarter.screenplay.domain.AgentTaskType;
import com.urke.saasbackendstarter.screenplay.domain.ScriptScene;
import com.urke.saasbackendstarter.screenplay.domain.ScriptVersion;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScreenplayProjectRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptSceneRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDraftAcceptanceServiceTest {
    @Mock private CurrentUserProvider currentUser;
    @Mock private AgentDraftRepository drafts;
    @Mock private ScreenplayProjectRepository projects;
    @Mock private ScriptVersionRepository scripts;
    @Mock private ScriptSceneRepository scenes;

    @Test
    void applies_patch_to_selected_duplicate_scene_without_losing_heading_or_action() {
        ScriptVersion source = new ScriptVersion();
        source.setId(10L);
        String sceneText = "INT. ROOM - NIGHT\nAction stays.\nALICE\nOld line.\n";
        source.setRawText(sceneText + sceneText);
        source.setContentRevision(1L);
        ScriptScene first = scene(101L, source, sceneText, 0);
        ScriptScene second = scene(102L, source, sceneText, 1);
        AgentDraft draft = rewriteDraft(source, "102",
                "{\"original\":\"ALICE\\nOld line.\",\"proposed\":\"ALICE\\nNew line.\",\"sourceOffset\":32}");

        when(drafts.findLockedByIdAndOrganizationId("draft-0001", 1L)).thenReturn(Optional.of(draft));
        when(scenes.findByIdAndScriptVersionId(102L, 10L)).thenReturn(Optional.of(second));
        when(scenes.findAllByScriptVersionIdOrderBySortOrderAsc(10L)).thenReturn(List.of(first, second));
        when(scripts.save(any(ScriptVersion.class))).thenAnswer(invocation -> {
            ScriptVersion accepted = invocation.getArgument(0);
            accepted.setId(11L);
            return accepted;
        });
        var user = new com.urke.saasbackendstarter.domain.User();
        var organization = new com.urke.saasbackendstarter.domain.Organization();
        organization.setId(1L);
        user.setOrganization(organization);
        when(currentUser.getCurrentUser()).thenReturn(user);

        service().accept("draft-0001");

        ArgumentCaptor<ScriptVersion> captured = ArgumentCaptor.forClass(ScriptVersion.class);
        org.mockito.Mockito.verify(scripts).save(captured.capture());
        assertThat(captured.getValue().getRawText()).isEqualTo(sceneText +
                "INT. ROOM - NIGHT\nAction stays.\nALICE\nNew line.\n");
    }

    @Test
    void rejects_patch_that_consumes_the_separator_before_the_next_scene() {
        ScriptVersion source = new ScriptVersion();
        source.setId(10L);
        source.setContentRevision(1L);
        String firstText = "INT. ROOM - NIGHT\nALICE\nOld line.\n";
        String secondText = "EXT. STREET - DAY\nA bus arrives.\n";
        source.setRawText(firstText + secondText);
        ScriptScene first = scene(101L, source, firstText, 0);
        ScriptScene second = scene(102L, source, secondText, 1);
        AgentDraft draft = rewriteDraft(source, "101",
                "{\"original\":\"Old line.\\n\",\"proposed\":\"New line.\",\"sourceOffset\":24}");
        authorize(draft);
        when(scenes.findByIdAndScriptVersionId(101L, 10L)).thenReturn(Optional.of(first));
        when(scenes.findAllByScriptVersionIdOrderBySortOrderAsc(10L)).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> service().accept("draft-0001"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("valid in-scene patch");
    }

    @Test
    void normalizes_crlf_before_validating_offsets_and_saving_the_new_version() {
        ScriptVersion source = new ScriptVersion();
        source.setId(10L);
        source.setContentRevision(1L);
        String sceneText = "INT. ROOM - NIGHT\r\nALICE\r\nOld line.\r\n";
        source.setRawText(sceneText);
        ScriptScene scene = scene(101L, source, sceneText, 0);
        AgentDraft draft = rewriteDraft(source, "101",
                "{\"original\":\"Old line.\",\"proposed\":\"New line.\\r\\nAgain.\",\"sourceOffset\":24}");
        authorize(draft);
        when(scenes.findByIdAndScriptVersionId(101L, 10L)).thenReturn(Optional.of(scene));
        when(scenes.findAllByScriptVersionIdOrderBySortOrderAsc(10L)).thenReturn(List.of(scene));
        when(scripts.save(any(ScriptVersion.class))).thenAnswer(invocation -> {
            ScriptVersion accepted = invocation.getArgument(0);
            accepted.setId(11L);
            return accepted;
        });

        service().accept("draft-0001");

        ArgumentCaptor<ScriptVersion> captured = ArgumentCaptor.forClass(ScriptVersion.class);
        org.mockito.Mockito.verify(scripts).save(captured.capture());
        assertThat(captured.getValue().getRawText()).isEqualTo("INT. ROOM - NIGHT\nALICE\nNew line.\nAgain.\n");
    }

    private AgentDraftAcceptanceService service() {
        return new AgentDraftAcceptanceService(currentUser, drafts, projects, scripts, scenes, new ObjectMapper());
    }

    private AgentDraft rewriteDraft(ScriptVersion source, String targetSceneId, String content) {
        AgentSession session = new AgentSession();
        session.setTaskType(AgentTaskType.REWRITE_DIALOGUE);
        AgentRun run = new AgentRun();
        run.setSession(session);
        AgentDraft draft = new AgentDraft();
        draft.setId("draft-0001");
        draft.setRun(run);
        draft.setSourceScript(source);
        draft.setSourceRevision(1L);
        draft.setTargetSceneId(targetSceneId);
        draft.setContent(content);
        draft.setStatus(AgentDraftStatus.PENDING_REVIEW);
        return draft;
    }

    private void authorize(AgentDraft draft) {
        when(drafts.findLockedByIdAndOrganizationId("draft-0001", 1L)).thenReturn(Optional.of(draft));
        var user = new com.urke.saasbackendstarter.domain.User();
        var organization = new com.urke.saasbackendstarter.domain.Organization();
        organization.setId(1L);
        user.setOrganization(organization);
        when(currentUser.getCurrentUser()).thenReturn(user);
    }

    private ScriptScene scene(Long id, ScriptVersion source, String text, int sortOrder) {
        ScriptScene scene = new ScriptScene();
        scene.setId(id);
        scene.setScriptVersion(source);
        scene.setRawText(text);
        scene.setSortOrder(sortOrder);
        return scene;
    }
}
