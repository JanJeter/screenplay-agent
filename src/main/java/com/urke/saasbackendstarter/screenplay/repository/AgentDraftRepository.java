package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.AgentDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface AgentDraftRepository extends JpaRepository<AgentDraft, String> {
    Optional<AgentDraft> findByOrganizationIdAndIdempotencyKey(Long organizationId, String idempotencyKey);
    boolean existsByRunId(String runId);
    Optional<AgentDraft> findByIdAndOrganizationId(String id, Long organizationId);
    List<AgentDraft> findAllBySourceScriptProjectIdAndOrganizationIdOrderByCreatedAtDesc(Long projectId, Long organizationId);
}
