package com.urke.saasbackendstarter.screenplay.service;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.screenplay.domain.AgentDraft;
import com.urke.saasbackendstarter.screenplay.domain.AgentDraftStatus;
import com.urke.saasbackendstarter.screenplay.domain.ScriptStatus;
import com.urke.saasbackendstarter.screenplay.domain.ScriptVersion;
import com.urke.saasbackendstarter.screenplay.dto.agent.AgentDraftSummary;
import com.urke.saasbackendstarter.screenplay.repository.AgentDraftRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScreenplayProjectRepository;
import com.urke.saasbackendstarter.screenplay.repository.ScriptVersionRepository;
import com.urke.saasbackendstarter.security.CurrentUserProvider;
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
        ScriptVersion accepted = new ScriptVersion();
        accepted.setProject(draft.getSourceScript().getProject());
        accepted.setVersionName("Agent draft " + draft.getId().substring(0, 8));
        accepted.setRawText(draft.getContent());
        accepted.setStatus(ScriptStatus.UPLOADED);
        accepted.setContentRevision(1L);
        scripts.save(accepted);
        draft.setAcceptedScript(accepted);
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
        return drafts.findByIdAndOrganizationId(id, orgId)
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
                draft.getAcceptedScript() == null ? null : Long.toString(draft.getAcceptedScript().getId()), draft.getCreatedAt());
    }
}
