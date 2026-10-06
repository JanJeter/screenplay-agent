package com.urke.saasbackendstarter.screenplay.service;

import org.springframework.http.HttpStatus;

/** Contract-shaped errors for the new storyboard API; legacy endpoints retain their existing error envelope. */
public class StoryboardApiException extends RuntimeException {
    private final HttpStatus status; private final String code; private final Long currentRevision;
    public StoryboardApiException(HttpStatus status, String code) { this(status, code, null); }
    public StoryboardApiException(HttpStatus status, String code, Long currentRevision) { super(code); this.status=status; this.code=code; this.currentRevision=currentRevision; }
    public HttpStatus status(){return status;} public String code(){return code;} public Long currentRevision(){return currentRevision;}
}
