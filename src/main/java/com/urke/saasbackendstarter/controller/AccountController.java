package com.urke.saasbackendstarter.controller;

import com.urke.saasbackendstarter.domain.User;
import com.urke.saasbackendstarter.dto.user.UserCreateRequest;
import com.urke.saasbackendstarter.exception.AccountApiException;
import com.urke.saasbackendstarter.repository.UserRepository;
import com.urke.saasbackendstarter.service.AccountLifecycleService;
import com.urke.saasbackendstarter.service.AccountRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AccountController {
    private final AccountLifecycleService accounts;
    private final AccountRateLimiter limits;
    private final UserRepository users;

    @PostMapping("/register")
    public Map<String, String> register(@Valid @RequestBody UserCreateRequest request, HttpServletRequest http) {
        limit(http);
        accounts.register(request);
        return Map.of("message", AccountLifecycleService.REGISTER_MESSAGE);
    }

    @PostMapping("/verify-email")
    public Map<String, String> verify(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        limit(http);
        accounts.verifyEmail(request.token());
        return Map.of("message", "邮箱验证成功，现在可以登录。");
    }

    @PostMapping("/resend-verification")
    public Map<String, String> resend(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        limit(http);
        accounts.resendVerification(request.email());
        return Map.of("message", AccountLifecycleService.RESEND_MESSAGE);
    }

    @PostMapping("/forgot-password")
    public Map<String, String> forgot(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
        limit(http);
        accounts.forgotPassword(request.email());
        return Map.of("message", AccountLifecycleService.FORGOT_MESSAGE);
    }

    @PostMapping("/reset-password")
    public Map<String, String> reset(@Valid @RequestBody ResetRequest request, HttpServletRequest http) {
        limit(http);
        accounts.resetPassword(request.token(), request.newPassword());
        return Map.of("message", "密码已重置，请使用新密码重新登录。");
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Profile me(Principal principal) {
        if (principal == null) throw new AccountApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "请先登录。");
        User user = users.findByEmailIgnoreCaseAndDeletedFalse(principal.getName())
                .orElseThrow(() -> new AccountApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "请重新登录。"));
        if (!user.isEnabled() || !user.isEmailVerified()) throw new AccountApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "请重新登录。");
        var details = new com.urke.saasbackendstarter.security.CustomUserDetails(user);
        return new Profile(user.getId(), user.getEmail(), user.getFullName(),
                details.getScopedRoles().stream().map(role -> role.getName()).sorted().toList(),
                details.getScopedPermissions().stream().map(permission -> permission.getName()).distinct().sorted().toList(),
                user.getOrganization().getId(), user.getOrganization().getName(), user.isEmailVerified(), user.isEnabled());
    }

    private void limit(HttpServletRequest request) {
        // Trust the connected peer, never a client-provided X-Forwarded-For header.
        limits.check("account-ip:" + request.getRemoteAddr(), 0, 60);
    }

    public record EmailRequest(@NotBlank @Size(max = 100) String email) { }
    public record TokenRequest(@NotBlank @Size(max = 128) String token) { }
    public record ResetRequest(@NotBlank @Size(max = 128) String token, @NotBlank @Size(min = 8, max = 64) String newPassword) { }
    public record Profile(Long id, String email, String fullName, List<String> roles, List<String> permissions,
                          Long organizationId, String organizationName, boolean emailVerified, boolean enabled) { }
}
