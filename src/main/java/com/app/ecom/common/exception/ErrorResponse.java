package com.app.ecom.common.exception;

import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
public class ErrorResponse {
    private final Instant timestamp = Instant.now();
    private final int status;
    private final String error;
    private final String message;
    private Map<String, String> fieldErrors;

    public ErrorResponse(int status, String error, String message) {
        this.status = status;
        this.error = error;
        this.message = message;
    }

    public ErrorResponse(int status, String error, String message, Map<String, String> fieldErrors) {
        this(status, error, message);
        this.fieldErrors = fieldErrors;
    }
}
