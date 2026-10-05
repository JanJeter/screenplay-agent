package com.urke.saasbackendstarter.screenplay.repository;

import com.urke.saasbackendstarter.screenplay.domain.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentSessionRepository extends JpaRepository<AgentSession, String> {
    java.util.Optional<AgentSession> findByIdAndOrganizationIdAndUserId(String id, Long organizationId, Long userId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update AgentSession s set s.leaseOwner = :owner, s.leaseExpiresAt = :expires "
            + "where s.id = :sessionId and (s.leaseExpiresAt is null or s.leaseExpiresAt < :now or s.leaseOwner = :owner)")
    int tryAcquireLease(@org.springframework.data.repository.query.Param("sessionId") String sessionId,
                        @org.springframework.data.repository.query.Param("owner") String owner,
                        @org.springframework.data.repository.query.Param("now") java.time.Instant now,
                        @org.springframework.data.repository.query.Param("expires") java.time.Instant expires);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update AgentSession s set s.leaseOwner = null, s.leaseExpiresAt = null where s.id = :sessionId and s.leaseOwner = :owner")
    int releaseLease(@org.springframework.data.repository.query.Param("sessionId") String sessionId,
                     @org.springframework.data.repository.query.Param("owner") String owner);
}
