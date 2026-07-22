package com.taskforge.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by AuthService when login fails due to bad credentials (wrong password or unknown email).
 *
 * <p><b>Why not distinguish "email not found" from "wrong password"?</b>
 * Returning different errors for each case enables user enumeration attacks — an attacker
 * can probe which emails are registered. A single generic 401 prevents this.
 * Maps to HTTP 401 Unauthorized.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
