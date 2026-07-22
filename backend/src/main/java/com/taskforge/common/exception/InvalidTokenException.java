package com.taskforge.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a refresh token is expired, revoked, or not found in the database.
 * Maps to HTTP 401 Unauthorized — the client must re-authenticate from scratch.
 *
 * <p>Intentionally does not distinguish between "not found", "revoked", and "expired"
 * to avoid leaking information about which tokens are in the system.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
