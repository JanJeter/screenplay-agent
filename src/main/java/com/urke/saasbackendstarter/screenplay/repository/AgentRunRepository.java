package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {
    java.util.Optional<AgentRun> findByIdAndSessionOrganizationIdAndSessionUserId(String id, Long organizationId, Long userId);
    java.util.Optional<AgentRun> findBySessionIdAndRequestId(String sessionId, String requestId);
    java.util.List<AgentRun> findAllByStatusIn(java.util.Collection<com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus> statuses);
    boolean existsBySessionIdAndStatusIn(String sessionId, java.util.Collection<com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus> statuses);
    long countByParentRunId(String parentRunId);
    java.util.Optional<AgentRun> findByParentRunIdAndRequestId(String parentRunId, String requestId);
    java.util.List<AgentRun> findAllByParentRunIdAndStatusIn(String parentRunId, java.util.Collection<com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus> statuses);
}
