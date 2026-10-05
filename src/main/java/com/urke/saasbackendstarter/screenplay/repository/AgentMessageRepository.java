package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.AgentMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentMessageRepository extends JpaRepository<AgentMessage, String> {
    Optional<AgentMessage> findTopBySessionIdOrderBySequenceDesc(String sessionId);
    List<AgentMessage> findTop12BySessionIdOrderBySequenceDesc(String sessionId);
}
