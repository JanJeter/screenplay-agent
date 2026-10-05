package com.urke.saasbackendstarter.screenplay.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_runs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agent_run_session_request", columnNames = {"session_id", "request_id"})
}, indexes = @Index(name = "idx_agent_runs_session", columnList = "session_id"))
@Getter
@Setter
@NoArgsConstructor
public class AgentRun {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    private AgentSession session;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_run_id")
    private AgentRun parentRun;

    @Column(name = "agent_id", nullable = false, length = 80)
    private String agentId = "general";

    @Column(name = "trace_id", nullable = false, length = 36)
    private String traceId;

    @Column(name = "gateway_run_id", length = 128)
    private String gatewayRunId;

    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "request_message", nullable = false, columnDefinition = "text")
    private String requestMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentRunStatus status = AgentRunStatus.QUEUED;

    @Column(name = "source_revision", nullable = false)
    private long sourceRevision;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
