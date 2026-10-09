package com.urke.saasbackendstarter.service.impl;

import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.dto.auth.*;
import com.urke.saasbackendstarter.exception.AccountApiException;
import com.urke.saasbackendstarter.repository.*;
import com.urke.saasbackendstarter.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    @Mock JwtTokenProvider jwtTokenProvider;
    @Mock UserRepository users;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock PasswordEncoder passwords;
    @Mock LoginAttemptService attempts;
    @Mock jakarta.persistence.EntityManager entityManager;
    @InjectMocks AuthServiceImpl service;
    private User user;
    @BeforeEach void setup() { user = User.builder().id(3L).email("user@example.test").password("hashed-password").roles(Set.of()).tokenVersion(2).build(); }
    private void loginUser() {
        when(users.findByEmailIgnoreCaseAndDeletedFalse(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findLockedById(user.getId())).thenReturn(Optional.of(user));
        when(passwords.matches(anyString(), eq(user.getPassword()))).thenReturn(true);
    }
    private RefreshToken refresh(String raw) {
        String hash = "sha256:" + AccountTokens.hash(raw);
        RefreshToken token = RefreshToken.builder().token(hash).user(user).tokenVersion(2).expiryDate(Instant.now().plusSeconds(1000)).build();
        when(refreshTokens.findOwnerId(hash)).thenReturn(Optional.of(user.getId()));
        when(users.findLockedById(user.getId())).thenReturn(Optional.of(user));
        when(refreshTokens.findByToken(hash)).thenReturn(Optional.of(token));
        return token;
    }
    private void fails(String code, Runnable action) {
        var error = catchThrowableOfType(action::run, AccountApiException.class);
        assertThat(error).isNotNull(); assertThat(error.code()).isEqualTo(code);
    }
    @Test void verifiedLoginNormalizesEmailAndStoresOnlyRefreshHash() {
        loginUser(); when(jwtTokenProvider.generateToken(any())).thenReturn("test-access-token");
        var result = service.login(new LoginRequest(" USER@EXAMPLE.TEST ", "test-only-password"));
        var stored = ArgumentCaptor.forClass(RefreshToken.class); verify(refreshTokens).save(stored.capture());
        assertThat(result.getAccessToken().equals("test-access-token")).isTrue();
        assertThat(stored.getValue().getToken().equals("sha256:" + AccountTokens.hash(result.getRefreshToken()))).isTrue();
        assertThat(stored.getValue().getTokenVersion()).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings = {"unverified", "disabled", "deleted"})
    void unusableAccountsCannotLogin(String state) {
        loginUser();
        if (state.equals("unverified")) user.setEmailVerified(false);
        if (state.equals("disabled")) user.setEnabled(false);
        if (state.equals("deleted")) user.setDeleted(true);
        fails(state.equals("unverified") ? "email_not_verified" : state.equals("disabled") ? "account_disabled" : "invalid_credentials",
                () -> service.login(new LoginRequest(user.getEmail(), "test-only-password")));
        verifyNoInteractions(jwtTokenProvider, refreshTokens);
    }
    @Test void wrongPasswordNeverDisclosesUnverifiedStatus() {
        user.setEmailVerified(false); when(users.findByEmailIgnoreCaseAndDeletedFalse(user.getEmail())).thenReturn(Optional.of(user));
        fails("invalid_credentials", () -> service.login(new LoginRequest(user.getEmail(), "test-only-password")));
        verify(attempts).loginFailed(user.getEmail()); verify(users, never()).findLockedById(any());
    }
    @Test void blockedLoginDoesNotReadCredentials() {
        when(attempts.isBlocked(user.getEmail())).thenReturn(true);
        fails("rate_limited", () -> service.login(new LoginRequest(user.getEmail(), "test-only-password")));
        verifyNoInteractions(users, passwords);
    }
    @Test void loginRechecksThePasswordAfterRefreshingTheLockedUser() {
        loginUser();
        doAnswer(invocation -> { user.setPassword("concurrently-reset-password-hash"); return null; }).when(entityManager).refresh(user);
        fails("invalid_credentials", () -> service.login(new LoginRequest(user.getEmail(), "test-only-password")));
        verifyNoInteractions(jwtTokenProvider, refreshTokens);
    }
    @Test void refreshRotatesAndBindsTheNewTokenToTheCurrentVersion() {
        String raw = AccountTokens.create(); refresh(raw); when(jwtTokenProvider.generateToken(any())).thenReturn("new-access-token");
        var response = service.refreshToken(new RefreshTokenRequest(raw));
        assertThat(response.getRefreshToken().equals(raw)).isFalse();
        verify(refreshTokens).deleteByUser(user); verify(refreshTokens).save(any());
    }
    @ParameterizedTest @ValueSource(strings = {"expired", "revoked", "disabled", "unverified", "deleted"})
    void refreshRejectsExpiredRevokedOrUnusableAccounts(String state) {
        String raw = AccountTokens.create(); var token = refresh(raw);
        if (state.equals("expired")) token.setExpiryDate(Instant.now().minusSeconds(1));
        if (state.equals("revoked")) user.setTokenVersion(3);
        if (state.equals("disabled")) user.setEnabled(false);
        if (state.equals("unverified")) user.setEmailVerified(false);
        if (state.equals("deleted")) user.setDeleted(true);
        fails(state.equals("disabled") ? "account_disabled" : state.equals("unverified") ? "email_not_verified" : "invalid_credentials",
                () -> service.refreshToken(new RefreshTokenRequest(raw)));
        verifyNoInteractions(jwtTokenProvider); verify(refreshTokens, never()).save(any());
    }
    @Test void logoutRevokesBothRefreshAndAccess() {
        when(users.findByEmailIgnoreCaseAndDeletedFalse(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findLockedById(user.getId())).thenReturn(Optional.of(user));
        service.logout(user.getEmail());
        verify(refreshTokens).deleteByUser(user); assertThat(user.getTokenVersion()).isEqualTo(3);
    }
}
