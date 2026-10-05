package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.AgentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentEventRepository extends JpaRepository<AgentEvent, String> {
    Optional<AgentEvent> findByRunIdAndSequence(String runId, long sequence);
    List<AgentEvent> findAllByRunIdAndSequenceGreaterThanOrderBySequenceAsc(String runId, long sequence);
    List<AgentEvent> findAllByRunIdOrderBySequenceAsc(String runId);
}
