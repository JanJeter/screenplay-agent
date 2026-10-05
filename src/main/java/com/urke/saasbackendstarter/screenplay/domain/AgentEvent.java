package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_events", uniqueConstraints = @UniqueConstraint(name = "uk_agent_event_run_sequence", columnNames = {"run_id", "sequence"}),
        indexes = @Index(name = "idx_agent_events_run", columnList = "run_id, sequence"))
@Getter
@Setter
@NoArgsConstructor
public class AgentEvent {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    private AgentRun run;

    @Column(nullable = false)
    private long sequence;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() { if (createdAt == null) createdAt = Instant.now(); }
}
