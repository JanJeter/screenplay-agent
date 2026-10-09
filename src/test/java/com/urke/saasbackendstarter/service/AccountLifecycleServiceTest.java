package com.urke.saasbackendstarter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.dto.user.UserCreateRequest;
import com.urke.saasbackendstarter.exception.AccountApiException;
import com.urke.saasbackendstarter.repository.*;
import com.urke.saasbackendstarter.security.AccountTokens;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountLifecycleServiceTest {
    @Mock UserRepository users;
    @Mock OrganizationRepository organizations;
    @Mock RoleRepository roles;
    @Mock AccountActionTokenRepository tokens;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock PasswordEncoder passwords;
    @Mock EmailService mail;
    @Mock AccountRateLimiter limits;
    @Mock EntityManager entityManager;
    @Mock Query lock;
    @InjectMocks AccountLifecycleService service;
    private Organization organization;
    private User user;

    @BeforeEach void setup() {
        organization = Organization.builder().id(7L).slug("demo-org").build();
        user = User.builder().id(11L).email("reader@example.test").fullName("Reader").organization(organization).roles(Set.of()).emailVerified(false).build();
        ReflectionTestUtils.setField(service, "registrationOrganizationSlug", "demo-org");
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://127.0.0.1:5174");
    }

    private UserCreateRequest request() { return UserCreateRequest.builder().email(" Reader@Example.Test ").fullName(" Reader ").password("test-only-password").build(); }
    private void registration() {
        when(organizations.findBySlugAndDeletedFalse("demo-org")).thenReturn(Optional.of(organization));
        when(entityManager.createNativeQuery(anyString())).thenReturn(lock);
        when(lock.setParameter(eq(1), any())).thenReturn(lock);
    }
    private AccountActionToken token(String raw, AccountActionToken.Purpose purpose) {
        AccountActionToken token = new AccountActionToken();
        token.setUser(user); token.setPurpose(purpose); token.setExpiresAt(Instant.now().plusSeconds(300));
        when(tokens.findOwnerId(AccountTokens.hash(raw), purpose)).thenReturn(Optional.of(user.getId()));
        when(users.findLockedById(user.getId())).thenReturn(Optional.of(user));
        when(tokens.findByTokenHashAndPurpose(AccountTokens.hash(raw), purpose)).thenReturn(Optional.of(token));
        return token;
    }
    private void fails(String code, Runnable action) {
        var error = catchThrowableOfType(action::run, AccountApiException.class);
        assertThat(error).isNotNull(); assertThat(error.code()).isEqualTo(code);
    }

    @Test void registrationAlwaysUsesConfiguredWorkspaceAndOnlyUserRole() {
        registration();
        Role role = Role.builder().name("USER").organization(organization).build();
        when(roles.findByNameAndOrganizationId("USER", 7L)).thenReturn(Optional.of(role));
        when(passwords.encode(anyString())).thenReturn("hashed-password");
        when(users.saveAndFlush(any())).thenAnswer(invocation -> { User saved = invocation.getArgument(0); saved.setId(11L); return saved; });
        User saved = service.register(request());
        assertThat(saved.getEmail()).isEqualTo("reader@example.test"); assertThat(saved.isEmailVerified()).isFalse();
        assertThat(saved.isEnabled()).isTrue(); assertThat(saved.getOrganization()).isSameAs(organization);
        assertThat(saved.getRoles()).containsExactly(role);
        var stored = ArgumentCaptor.forClass(AccountActionToken.class);
        var body = ArgumentCaptor.forClass(String.class);
        verify(tokens).save(stored.capture()); verify(mail).sendEmail(eq(saved.getEmail()), contains("验证"), body.capture());
        assertThat(stored.getValue().getTokenHash().matches("[0-9a-f]{64}")).isTrue();
        String raw = body.getValue().split("token=")[1].split("\\s")[0];
        assertThat(stored.getValue().getTokenHash().equals(AccountTokens.hash(raw))).isTrue();
        assertThat(stored.getValue().getTokenHash().equals(raw)).isFalse();
        verify(limits).check("register:reader@example.test", 60, 5);
    }

    @Test void publicRegistrationCannotJoinAnArbitraryOrganization() {
        when(organizations.findBySlugAndDeletedFalse("demo-org")).thenReturn(Optional.of(organization));
        var request = request(); request.setOrganizationId(999L);
        fails("validation_error", () -> service.register(request));
        verifyNoInteractions(users, mail, tokens);
    }

    @Test void duplicateRegistrationDoesNotChangePasswordOrExposeUser() {
        registration(); when(users.findByEmailIgnoreCase("reader@example.test")).thenReturn(Optional.of(user));
        service.register(request());
        verify(users, never()).saveAndFlush(any()); verifyNoInteractions(passwords, tokens, mail);
        verify(limits).check("register:reader@example.test", 60, 5);
    }

    @Test void unknownAndIneligibleMailRequestsAreStillRateLimited() {
        service.forgotPassword(" missing@example.test "); service.resendVerification("missing@example.test");
        verify(limits).check("reset-mail:missing@example.test", 60, 5);
        verify(limits).check("verify-mail:missing@example.test", 60, 5); verifyNoInteractions(tokens, mail);
    }

    @Test void verificationConsumesTokenOnce() {
        String raw = AccountTokens.create(); var action = token(raw, AccountActionToken.Purpose.VERIFY_EMAIL);
        service.verifyEmail(raw);
        assertThat(user.isEmailVerified()).isTrue(); assertThat(action.getConsumedAt()).isNotNull();
        fails("invalid_token", () -> service.verifyEmail(raw));
    }

    @Test void expiredAndDisabledTokensCannotVerifyAnAccount() {
        String raw = AccountTokens.create(); var action = token(raw, AccountActionToken.Purpose.VERIFY_EMAIL);
        action.setExpiresAt(Instant.now().minusSeconds(1)); fails("invalid_token", () -> service.verifyEmail(raw));
        action.setExpiresAt(Instant.now().plusSeconds(100)); user.setEnabled(false); fails("invalid_token", () -> service.verifyEmail(raw));
        assertThat(user.isEmailVerified()).isFalse(); verify(tokens, never()).save(any());
    }

    @Test void resetRevokesRefreshAndAccessVersionsAndCannotBeReplayed() {
        user.setEmailVerified(true); user.setTokenVersion(4);
        String raw = AccountTokens.create(); token(raw, AccountActionToken.Purpose.RESET_PASSWORD);
        when(passwords.encode(anyString())).thenReturn("new-password-hash");
        service.resetPassword(raw, "new-test-password");
        assertThat(user.getTokenVersion()).isEqualTo(5); verify(refreshTokens).deleteByUser(user);
        fails("invalid_token", () -> service.resetPassword(raw, "another-test-password"));
        verify(passwords, times(1)).encode(anyString());
    }

    @Test void resetRejectsWeakPasswordBeforeLookingUpToken() {
        fails("validation_error", () -> service.resetPassword(AccountTokens.create(), "short"));
        verifyNoInteractions(tokens, users, refreshTokens);
    }

    @Test void roleInjectionIsRejectedByRegistrationDto() {
        assertThatThrownBy(() -> new ObjectMapper().readValue("{\"roles\":[\"ADMIN\"]}", UserCreateRequest.class))
                .hasRootCauseInstanceOf(AccountApiException.class);
    }

    @Test void mailFailureDoesNotExposeTheProviderExceptionOrToken() {
        when(users.findByEmailIgnoreCaseAndDeletedFalse(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findLockedById(user.getId())).thenReturn(Optional.of(user));
        doThrow(new IllegalStateException("private provider transport diagnostic")).when(mail).sendEmail(anyString(), anyString(), anyString());
        fails("mail_unavailable", () -> service.resendVerification(user.getEmail()));
    }
}
