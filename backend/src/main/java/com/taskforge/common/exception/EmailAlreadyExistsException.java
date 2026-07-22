package com.taskforge.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by AuthService when a signup request uses an email that already exists.
 * Maps to HTTP 409 Conflict via @ResponseStatus (or GlobalExceptionHandler).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException(String email) {
        super("Email address is already registered: " + email);
    }
}
