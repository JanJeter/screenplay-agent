package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.AgentOutboxStatus;
import com.urke.saasbackendstarter.screenplay.domain.AgentRunOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentRunOutboxRepository extends JpaRepository<AgentRunOutbox, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AgentRunOutbox> findByRunId(String runId);

    @Query("select o.run.id from AgentRunOutbox o where o.status = :status and o.nextAttemptAt <= :now order by o.nextAttemptAt asc")
    List<String> findReadyRunIds(AgentOutboxStatus status, Instant now, org.springframework.data.domain.Pageable pageable);
    List<AgentRunOutbox> findAllByStatus(AgentOutboxStatus status);
}
