package com.urke.saasbackendstarter.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountExceptionHandler {
    @ExceptionHandler(AccountApiException.class)
    public ResponseEntity<AccountError> accountError(AccountApiException error) {
        return ResponseEntity.status(error.status()).body(new AccountError(error.code(), error.getMessage()));
    }

    public record AccountError(String code, String message) { }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<AccountError> invalidJson() {
        return ResponseEntity.badRequest().body(new AccountError("validation_error", "请求内容无效，请检查输入。"));
    }
}
