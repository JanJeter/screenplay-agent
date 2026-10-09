package com.urke.saasbackendstarter.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "account_action_tokens", indexes = @Index(name = "idx_account_token_user_purpose", columnList = "user_id,purpose"))
@Getter @Setter @NoArgsConstructor
public class AccountActionToken {
    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD }

    @Id
    private String id;
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Purpose purpose;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false)
    private Instant expiresAt;
    private Instant consumedAt;
}
