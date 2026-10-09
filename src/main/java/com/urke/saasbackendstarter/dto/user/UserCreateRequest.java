package com.urke.saasbackendstarter.dto.user;

import jakarta.validation.constraints.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.urke.saasbackendstarter.security.AccountInputs;
import java.util.Locale;

/**
 * DTO for user registration (input).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCreateRequest {

    @Email(message = "Email must be valid")
    @NotBlank(message = "Email is required")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 64, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank(message = "Full name is required")
    @Size(max = 50)
    private String fullName;

    // Legacy callers may supply only the configured workspace ID. It is never trusted for membership.
    private Long organizationId;

    public void setEmail(String email) { this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT); }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw AccountInputs.invalid("注册请求包含不支持的字段。");
    }
}
