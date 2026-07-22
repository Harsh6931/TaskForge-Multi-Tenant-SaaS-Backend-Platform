package com.taskforge.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SignupRequest — request body for {@code POST /auth/signup}.
 *
 * <p>Java record: immutable, auto-generates constructor, getters, equals/hashCode, toString.
 * Bean Validation annotations are processed by {@code @Valid} in the controller —
 * a 400 Bad Request with field errors is returned if any constraint fails
 * (handled by {@link com.taskforge.common.exception.GlobalExceptionHandler}).
 */
public record SignupRequest(

        /**
         * Must be a valid RFC 5321 email address.
         * {@code @Email} validates format; uniqueness is enforced by the DB unique constraint
         * and checked explicitly in AuthService before INSERT.
         */
        @Email(message = "Must be a valid email address")
        @NotBlank(message = "Email is required")
        String email,

        /**
         * Plaintext password — never persisted as-is.
         * AuthService will immediately BCrypt-encode it before saving to the DB.
         * Minimum 8 characters enforced here; consider adding complexity rules for production.
         */
        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        String password,

        /**
         * Display name shown in the UI and in task assignments.
         */
        @NotBlank(message = "Full name is required")
        String fullName
) {}
