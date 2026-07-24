package com.taskforge.common.exception;

import java.util.UUID;

/**
 * CommentAccessDeniedException — thrown when a user attempts to delete a comment
 * that they did not author and do not have a privileged role (ADMIN or MANAGER).
 *
 * <p>Maps to HTTP 403 Forbidden in {@link GlobalExceptionHandler}.
 */
public class CommentAccessDeniedException extends RuntimeException {

    /**
     * @param commentId the comment the user tried to delete
     * @param userId    the user who made the request
     */
    public CommentAccessDeniedException(UUID commentId, UUID userId) {
        super("User " + userId + " is not allowed to delete comment " + commentId +
              " — must be the author, ADMIN, or MANAGER");
    }
}
