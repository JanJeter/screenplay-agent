package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_messages", uniqueConstraints = @UniqueConstraint(name = "uk_agent_message_session_sequence", columnNames = {"session_id", "sequence"}),
        indexes = @Index(name = "idx_agent_messages_session", columnList = "session_id, sequence"))
@Getter
@Setter
@NoArgsConstructor
public class AgentMessage {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private AgentSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", updatable = false)
    private AgentRun run;

    @Column(nullable = false)
    private long sequence;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
