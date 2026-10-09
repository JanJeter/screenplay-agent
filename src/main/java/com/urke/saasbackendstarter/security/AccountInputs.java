package com.urke.saasbackendstarter.security;

import com.urke.saasbackendstarter.exception.AccountApiException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class AccountInputs {
    private AccountInputs() { }

    public static String email(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 80 || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw invalid("请输入有效邮箱，长度不超过 80 个字符。");
        }
        return normalized;
    }

    public static String password(String value) {
        if (value == null || value.isBlank() || value.length() < 8 || value.length() > 64
                || value.getBytes(StandardCharsets.UTF_8).length > 72 || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("密码需为 8–64 个字符，UTF-8 长度不超过 72 字节，不能包含控制字符。");
        }
        return value;
    }

    public static String fullName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank() || name.length() > 50 || name.chars().anyMatch(Character::isISOControl)) throw invalid("姓名需为 1–50 个字符。");
        return name;
    }

    public static AccountApiException invalid(String message) {
        return new AccountApiException(HttpStatus.BAD_REQUEST, "validation_error", message);
    }
}
