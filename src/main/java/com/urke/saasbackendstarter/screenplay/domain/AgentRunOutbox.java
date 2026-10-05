package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_run_outbox", uniqueConstraints = @UniqueConstraint(name = "uk_agent_outbox_run", columnNames = "run_id"),
        indexes = @Index(name = "idx_agent_outbox_pending", columnList = "status, next_attempt_at"))
@Getter
@Setter
@NoArgsConstructor
public class AgentRunOutbox {
    @Id
    @Column(length = 36)
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    private AgentRun run;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentOutboxStatus status = AgentOutboxStatus.PENDING;

    @Column(nullable = false)
    private int attempt = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (nextAttemptAt == null) nextAttemptAt = createdAt;
    }
}
