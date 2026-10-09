package com.urke.saasbackendstarter.service;

import com.urke.saasbackendstarter.exception.AccountApiException;
import com.urke.saasbackendstarter.security.AccountTokens;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** PostgreSQL atomically reserves a request slot, including for unknown email addresses. */
@Service
@RequiredArgsConstructor
public class AccountRateLimiter {
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void check(String key, int cooldownSeconds, int hourlyLimit) {
        Instant now = Instant.now();
        var updated = entityManager.createNativeQuery("""
                INSERT INTO account_action_limits (id, next_allowed_at, window_started_at, requests)
                VALUES (?1, ?2, ?3, 1)
                ON CONFLICT (id) DO UPDATE SET next_allowed_at = EXCLUDED.next_allowed_at,
                  window_started_at = CASE WHEN account_action_limits.window_started_at <= ?4 THEN EXCLUDED.window_started_at ELSE account_action_limits.window_started_at END,
                  requests = CASE WHEN account_action_limits.window_started_at <= ?4 THEN 1 ELSE account_action_limits.requests + 1 END
                WHERE account_action_limits.next_allowed_at <= ?3
                  AND (account_action_limits.window_started_at <= ?4 OR account_action_limits.requests < ?5)
                RETURNING id
                """).setParameter(1, AccountTokens.hash(key)).setParameter(2, now.plusSeconds(cooldownSeconds))
                .setParameter(3, now).setParameter(4, now.minusSeconds(3600)).setParameter(5, hourlyLimit).getResultList();
        if (updated.isEmpty()) throw new AccountApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "操作过于频繁，请稍后再试。");
    }
}
