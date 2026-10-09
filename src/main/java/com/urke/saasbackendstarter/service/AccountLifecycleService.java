package com.urke.saasbackendstarter.service;

import com.urke.saasbackendstarter.domain.*;
import com.urke.saasbackendstarter.dto.user.UserCreateRequest;
import com.urke.saasbackendstarter.exception.AccountApiException;
import com.urke.saasbackendstarter.repository.*;
import com.urke.saasbackendstarter.security.AccountInputs;
import com.urke.saasbackendstarter.security.AccountTokens;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountLifecycleService {
    public static final String REGISTER_MESSAGE = "如果该邮箱可以注册，验证邮件已发送，请检查收件箱。";
    public static final String RESEND_MESSAGE = "如果该邮箱需要验证，我们已发送新的验证邮件。";
    public static final String FORGOT_MESSAGE = "如果该邮箱可以找回密码，我们已发送重置邮件。";
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final RoleRepository roles;
    private final AccountActionTokenRepository tokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwords;
    private final EmailService mail;
    private final AccountRateLimiter limits;
    private final EntityManager entityManager;

    @Value("${auth.registration-organization-id:0}")
    private long registrationOrganizationId;
    @Value("${auth.registration-organization-slug:demo-org}")
    private String registrationOrganizationSlug;
    @Value("${auth.frontend-base-url:http://127.0.0.1:5174}")
    private String frontendBaseUrl;

    @Transactional
    public User register(UserCreateRequest request) {
        String email = AccountInputs.email(request.getEmail());
        String password = AccountInputs.password(request.getPassword());
        String name = AccountInputs.fullName(request.getFullName());
        Organization organization = registrationOrganizationId > 0
                ? organizations.findByIdAndDeletedFalse(registrationOrganizationId).orElseThrow(this::configurationError)
                : organizations.findBySlugAndDeletedFalse(registrationOrganizationSlug).orElseThrow(this::configurationError);
        if (request.getOrganizationId() != null && !request.getOrganizationId().equals(organization.getId())) {
            throw AccountInputs.invalid("注册工作区由服务端指定。");
        }
        limits.check("register:" + email, 60, 5);
        // Serialize same-email registration, even before a user row exists.
        entityManager.createNativeQuery("select pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, "account-registration:" + email).getSingleResult();
        var existing = users.findByEmailIgnoreCase(email);
        if (existing.isPresent()) return existing.get();
        Role role = roles.findByNameAndOrganizationId("USER", organization.getId()).orElseThrow(this::configurationError);
        User user = users.saveAndFlush(User.builder().email(email).password(passwords.encode(password)).fullName(name)
                .organization(organization).roles(Set.of(role)).emailVerified(false).enabled(true).deleted(false).build());
        issue(user, AccountActionToken.Purpose.VERIFY_EMAIL);
        return user;
    }

    @Transactional
    public void resendVerification(String rawEmail) {
        String email = AccountInputs.email(rawEmail);
        limits.check("verify-mail:" + email, 60, 5);
        users.findByEmailIgnoreCaseAndDeletedFalse(email).ifPresent(found -> {
            User user = users.findLockedById(found.getId()).orElseThrow();
            entityManager.refresh(user);
            if (!user.isDeleted() && user.isEnabled() && !user.isEmailVerified()) issue(user, AccountActionToken.Purpose.VERIFY_EMAIL);
        });
    }

    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = AccountInputs.email(rawEmail);
        limits.check("reset-mail:" + email, 60, 5);
        users.findByEmailIgnoreCaseAndDeletedFalse(email).ifPresent(found -> {
            User user = users.findLockedById(found.getId()).orElseThrow();
            entityManager.refresh(user);
            if (!user.isDeleted() && user.isEnabled() && user.isEmailVerified()) issue(user, AccountActionToken.Purpose.RESET_PASSWORD);
        });
    }

    @Transactional
    public void verifyEmail(String token) {
        AccountActionToken action = consume(token, AccountActionToken.Purpose.VERIFY_EMAIL);
        User user = action.getUser();
        user.setEmailVerified(true);
        users.save(user);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        String password = AccountInputs.password(newPassword);
        AccountActionToken action = consume(token, AccountActionToken.Purpose.RESET_PASSWORD);
        User user = action.getUser();
        if (!user.isEmailVerified()) throw invalidToken();
        user.setPassword(passwords.encode(password));
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokens.deleteByUser(user);
        users.save(user);
    }

    private AccountActionToken consume(String token, AccountActionToken.Purpose purpose) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        String hash = AccountTokens.hash(token);
        Long ownerId = tokens.findOwnerId(hash, purpose).orElseThrow(this::invalidToken);
        User user = users.findLockedById(ownerId).orElseThrow(this::invalidToken);
        entityManager.refresh(user);
        AccountActionToken action = tokens.findByTokenHashAndPurpose(hash, purpose).orElseThrow(this::invalidToken);
        if (action.getConsumedAt() != null || !action.getExpiresAt().isAfter(Instant.now()) || user.isDeleted() || !user.isEnabled()) throw invalidToken();
        action.setConsumedAt(Instant.now());
        tokens.save(action);
        return action;
    }

    private void issue(User user, AccountActionToken.Purpose purpose) {
        tokens.deleteByUserIdAndPurpose(user.getId(), purpose);
        String token = AccountTokens.create();
        AccountActionToken action = new AccountActionToken();
        action.setId(UUID.randomUUID().toString());
        action.setUser(user);
        action.setTokenHash(AccountTokens.hash(token));
        action.setPurpose(purpose);
        action.setExpiresAt(Instant.now().plusSeconds(purpose == AccountActionToken.Purpose.VERIFY_EMAIL ? 86400 : 900));
        tokens.save(action);
        boolean verify = purpose == AccountActionToken.Purpose.VERIFY_EMAIL;
        String base = frontendBaseUrl.replaceAll("/+$", "");
        URI uri = URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) throw configurationError();
        String link = base + (verify ? "/verify-email?token=" : "/reset-password?token=") + token;
        try {
            mail.sendEmail(user.getEmail(), verify ? "分镜工作台：验证邮箱" : "分镜工作台：重置密码",
                    (verify ? "请打开以下链接验证邮箱，链接 24 小时内有效：\n" : "请打开以下链接重置密码，链接 15 分钟内有效：\n")
                            + link + "\n\n如果不是你本人操作，请忽略此邮件。");
        } catch (RuntimeException failure) {
            // Never attach SMTP exceptions: providers can include credentials or message contents.
            throw new AccountApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail_unavailable", "邮件暂时无法发送，请稍后再试。");
        }
    }

    private AccountApiException invalidToken() { return new AccountApiException(HttpStatus.BAD_REQUEST, "invalid_token", "链接无效、已使用或已过期，请重新申请。"); }
    private AccountApiException configurationError() { return new AccountApiException(HttpStatus.SERVICE_UNAVAILABLE, "auth_configuration_invalid", "账号服务配置尚未完成。"); }
}
