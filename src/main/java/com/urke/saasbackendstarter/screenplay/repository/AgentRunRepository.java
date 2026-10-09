package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.AgentRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AgentRunRepository extends JpaRepository<AgentRun, String> {
    @org.springframework.data.jpa.repository.Query("select r from AgentRun r where r.session.organization.id = :org "
            + "and (:status is null or r.status = :status) and (:type is null or r.session.taskType = :type)")
    org.springframework.data.domain.Page<AgentRun> searchForAdmin(
            @org.springframework.data.repository.query.Param("org") Long organizationId,
            @org.springframework.data.repository.query.Param("status") com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus status,
            @org.springframework.data.repository.query.Param("type") com.urke.saasbackendstarter.screenplay.domain.AgentTaskType taskType,
            org.springframework.data.domain.Pageable pageable);
    java.util.Optional<AgentRun> findByIdAndSessionOrganizationId(String id, Long organizationId);
    java.util.List<AgentRun> findAllBySessionOrganizationId(Long organizationId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select run from AgentRun run where run.id = :id")
    java.util.Optional<AgentRun> findLockedById(@org.springframework.data.repository.query.Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select run from AgentRun run where run.id = :id "
            + "and run.session.organization.id = :organizationId and run.session.user.id = :userId")
    java.util.Optional<AgentRun> findLockedByIdAndSessionOrganizationIdAndSessionUserId(
            @org.springframework.data.repository.query.Param("id") String id,
            @org.springframework.data.repository.query.Param("organizationId") Long organizationId,
            @org.springframework.data.repository.query.Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select run from AgentRun run where run.parentRun.id = :parentRunId and run.status in :statuses")
    java.util.List<AgentRun> findAllLockedByParentRunIdAndStatusIn(
            @org.springframework.data.repository.query.Param("parentRunId") String parentRunId,
            @org.springframework.data.repository.query.Param("statuses") java.util.Collection<com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus> statuses);

    java.util.Optional<AgentRun> findByIdAndSessionOrganizationIdAndSessionUserId(String id, Long organizationId, Long userId);
    java.util.Optional<AgentRun> findBySessionIdAndRequestId(String sessionId, String requestId);
    java.util.List<AgentRun> findAllByStatusIn(java.util.Collection<com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus> statuses);
    boolean existsBySessionIdAndStatusIn(String sessionId, java.util.Collection<com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus> statuses);
    long countByParentRunId(String parentRunId);
    java.util.Optional<AgentRun> findByParentRunIdAndRequestId(String parentRunId, String requestId);
    java.util.List<AgentRun> findAllByParentRunIdAndStatusIn(String parentRunId, java.util.Collection<com.urke.saasbackendstarter.screenplay.domain.AgentRunStatus> statuses);
    java.util.Optional<AgentRun> findBySessionOrganizationIdAndSessionUserIdAndRequestIdAndSessionTaskType(Long organizationId, Long userId, String requestId, com.urke.saasbackendstarter.screenplay.domain.AgentTaskType taskType);
}
