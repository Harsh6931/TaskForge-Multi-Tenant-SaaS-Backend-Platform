package com.taskforge.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GlobalExceptionHandler — centralised HTTP error translation for all controllers.
 *
 * <p><b>Why @RestControllerAdvice?</b>
 * Without this, uncaught exceptions result in Spring Boot's default whitelabel error page
 * or a generic 500. This handler catches specific exception types and returns structured
 * JSON error responses, matching the API contract's error shape.
 *
 * <p><b>RFC 7807 Problem Detail:</b>
 * We use Spring 6's built-in {@link ProblemDetail} instead of a custom error DTO.
 * It provides a standard JSON structure:
 * <pre>
 * {
 *   "type":   "about:blank",
 *   "title":  "Conflict",
 *   "status": 409,
 *   "detail": "Email address is already registered: bob@example.com"
 * }
 * </pre>
 *
 * <p><b>Logging strategy:</b>
 * Business errors (4xx) are logged at WARN — they're expected and actionable.
 * Unexpected errors (5xx) would be logged at ERROR — not handled here; let them bubble.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── 409 Conflict ──────────────────────────────────────────────────────────

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        log.warn("Signup conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Email Already Registered");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    // ── 401 Unauthorized ──────────────────────────────────────────────────────

    @ExceptionHandler(InvalidCredentialsException.class)
    public ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Authentication Failed");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ProblemDetail handleInvalidToken(InvalidTokenException ex) {
        log.warn("Token validation failed: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Invalid Token");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    // ── 404 Not Found ──────────────────────────────────────────────────────────

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource Not Found");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    // ── 409 Conflict — Optimistic Lock ─────────────────────────────────────────

    @ExceptionHandler(OptimisticLockConflictException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockConflictException ex) {
        log.warn("Optimistic lock conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Concurrent Modification Conflict");
        problem.setType(URI.create("about:blank"));
        problem.setProperty("hint", "Fetch the latest task version and retry the request.");
        return problem;
    }

    // ── 422 Unprocessable Entity — Invalid Status Transition ───────────────────

    @ExceptionHandler(InvalidStatusTransitionException.class)
    public ProblemDetail handleInvalidStatusTransition(InvalidStatusTransitionException ex) {
        log.warn("Invalid status transition: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Invalid Status Transition");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    // ── 403 Forbidden ─────────────────────────────────────────────────────────

    @ExceptionHandler(TenantAccessDeniedException.class)
    public ProblemDetail handleTenantAccessDenied(TenantAccessDeniedException ex) {
        log.warn("Tenant access denied: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Access Denied");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    @ExceptionHandler(CommentAccessDeniedException.class)
    public ProblemDetail handleCommentAccessDenied(CommentAccessDeniedException ex) {
        log.warn("Comment access denied: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Comment Access Denied");
        problem.setType(URI.create("about:blank"));
        return problem;
    }

    // ── 400 Bad Request — Bean Validation failures ────────────────────────────

    /**
     * Handles @Valid failures on @RequestBody — converts the list of field errors
     * into a structured map so the client knows exactly which fields are invalid.
     *
     * <p>Response body example:
     * <pre>
     * {
     *   "type":   "about:blank",
     *   "title":  "Validation Failed",
     *   "status": 400,
     *   "detail": "Request contains invalid fields",
     *   "errors": {
     *     "email":    "Must be a valid email address",
     *     "password": "Password must be at least 8 characters"
     *   }
     * }
     * </pre>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        // If multiple violations on same field, concatenate messages
                        (existing, replacement) -> existing + "; " + replacement
                ));

        log.warn("Validation failed: {}", errors);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request contains invalid fields");
        problem.setTitle("Validation Failed");
        problem.setType(URI.create("about:blank"));
        problem.setProperty("errors", errors);
        return problem;
    }
}
