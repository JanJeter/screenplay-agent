package com.urke.saasbackendstarter.exception;

import org.springframework.http.HttpStatus;

public class AccountApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AccountApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
