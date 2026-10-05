package com.urke.saasbackendstarter.screenplay.domain;

import com.urke.saasbackendstarter.domain.Organization;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_drafts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_agent_draft_org_key", columnNames = {"organization_id", "idempotency_key"})
}, indexes = @Index(name = "idx_agent_drafts_run", columnList = "run_id"))
@Getter
@Setter
@NoArgsConstructor
public class AgentDraft {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    private AgentRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_script_id", nullable = false, updatable = false)
    private ScriptVersion sourceScript;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accepted_script_id")
    private ScriptVersion acceptedScript;

    @Column(name = "source_revision", nullable = false, updatable = false)
    private long sourceRevision;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentDraftStatus status = AgentDraftStatus.PENDING_REVIEW;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
