package com.urke.saasbackendstarter.service.impl;

import com.urke.saasbackendstarter.domain.RefreshToken;
import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.dto.auth.*;
import com.urke.saasbackendstarter.exception.AccountApiException;
import com.urke.saasbackendstarter.repository.RefreshTokenRepository;
import com.urke.saasbackendstarter.repository.UserRepository;
import com.urke.saasbackendstarter.security.*;
import com.urke.saasbackendstarter.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import jakarta.persistence.EntityManager;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final LoginAttemptService attempts;
    private final EntityManager entityManager;
    // A fixed BCrypt workload also applies to unknown users. This is a hash, not a usable credential.
    private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    @Value("${jwt.refresh-token-duration-ms:604800000}")
    private long refreshTokenDurationMs = 604800000;

    @Override
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String email = AccountInputs.email(request.getEmail());
        if (attempts.isBlocked(email)) throw new AccountApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "登录尝试过于频繁，请稍后再试。");
        User found = users.findByEmailIgnoreCaseAndDeletedFalse(email).orElse(null);
        String password = request.getPassword();
        boolean matched = password != null && password.length() <= 64
                && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
                && passwords.matches(password, found == null ? DUMMY_HASH : found.getPassword());
        if (!matched || found == null) {
            attempts.loginFailed(email);
            throw credentials();
        }
        User user = users.findLockedById(found.getId()).orElseThrow(this::credentials);
        entityManager.refresh(user);
        // Password can have changed while the first BCrypt comparison ran.
        if (!passwords.matches(password, user.getPassword())) throw credentials();
        requireUsable(user);
        attempts.loginSucceeded(email);
        String accessToken = jwtTokenProvider.generateToken(new CustomUserDetails(user));
        return new LoginResponse(accessToken, createRefreshToken(user));
    }

    @Override
    @Transactional
    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
        String raw = request.getRefreshToken();
        if (raw == null || raw.isBlank() || raw.length() > 128) throw credentials();
        String stored = "sha256:" + AccountTokens.hash(raw);
        Long owner = refreshTokens.findOwnerId(stored).orElse(null);
        // Existing one-week sessions can migrate once; all new refresh tokens are hashed.
        if (owner == null && raw.matches("[0-9a-fA-F-]{36}")) {
            stored = raw;
            owner = refreshTokens.findOwnerId(stored).orElse(null);
        }
        if (owner == null) throw credentials();
        User user = users.findLockedById(owner).orElseThrow(this::credentials);
        entityManager.refresh(user);
        RefreshToken token = refreshTokens.findByToken(stored).orElseThrow(this::credentials);
        if (!token.getExpiryDate().isAfter(Instant.now()) || token.getTokenVersion() != user.getTokenVersion()) throw credentials();
        requireUsable(user);
        String access = jwtTokenProvider.generateToken(new CustomUserDetails(user));
        return new RefreshTokenResponse(access, createRefreshToken(user));
    }

    @Override
    @Transactional
    public void logout(String email) {
        User found = users.findByEmailIgnoreCaseAndDeletedFalse(AccountInputs.email(email)).orElseThrow(this::credentials);
        User user = users.findLockedById(found.getId()).orElseThrow(this::credentials);
        entityManager.refresh(user);
        refreshTokens.deleteByUser(user);
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);
        attempts.loginSucceeded(user.getEmail());
    }

    private String createRefreshToken(User user) {
        refreshTokens.deleteByUser(user);
        // Ensure old rows are deleted before inserting the rotated token.
        refreshTokens.flush();
        String raw = AccountTokens.create();
        refreshTokens.save(RefreshToken.builder().token("sha256:" + AccountTokens.hash(raw)).user(user)
                .expiryDate(Instant.now().plusMillis(refreshTokenDurationMs)).tokenVersion(user.getTokenVersion()).build());
        return raw;
    }

    private void requireUsable(User user) {
        if (user.isDeleted()) throw credentials();
        if (!user.isEnabled()) throw new AccountApiException(HttpStatus.FORBIDDEN, "account_disabled", "账号已停用，请联系管理员。");
        if (!user.isEmailVerified()) throw new AccountApiException(HttpStatus.FORBIDDEN, "email_not_verified", "请先验证邮箱，再登录。");
    }

    private AccountApiException credentials() { return new AccountApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "邮箱或密码错误，或登录凭据已失效。"); }
}
