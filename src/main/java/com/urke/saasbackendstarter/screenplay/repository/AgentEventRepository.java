package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.AgentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentEventRepository extends JpaRepository<AgentEvent, String> {
    @org.springframework.data.jpa.repository.Query("select e from AgentEvent e where e.run.session.organization.id = :org "
            + "and e.run.id in :ids and e.type in :types order by e.run.id, e.sequence")
    List<AgentEvent> findAdminUsageEvents(
            @org.springframework.data.repository.query.Param("org") Long organizationId,
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<String> runIds,
            @org.springframework.data.repository.query.Param("types") java.util.Collection<String> types);
    Optional<AgentEvent> findByRunIdAndSequence(String runId, long sequence);
    List<AgentEvent> findAllByRunIdAndSequenceGreaterThanOrderBySequenceAsc(String runId, long sequence);
    List<AgentEvent> findAllByRunIdOrderBySequenceAsc(String runId);
}
