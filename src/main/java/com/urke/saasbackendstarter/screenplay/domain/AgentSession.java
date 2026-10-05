package com.urke.saasbackendstarter.screenplay.domain;

import com.urke.saasbackendstarter.domain.Organization;
import com.urke.saasbackendstarter.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "agent_sessions", indexes = {
        @Index(name = "idx_agent_sessions_org", columnList = "organization_id"),
        @Index(name = "idx_agent_sessions_project", columnList = "project_id")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentSession {
    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private ScreenplayProject project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "script_id", nullable = false, updatable = false)
    private ScriptVersion script;

    @Column(name = "gateway_session_id", length = 128)
    private String gatewaySessionId;

    @Column(name = "profile_version", nullable = false, length = 64)
    private String profileVersion = "v1";

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 40)
    private AgentTaskType taskType = AgentTaskType.GENERAL;

    @Column(name = "lease_owner", length = 36)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
