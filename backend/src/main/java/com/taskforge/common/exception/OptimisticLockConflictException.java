package com.taskforge.common.exception;

import java.util.UUID;

/**
 * OptimisticLockConflictException — thrown when a PATCH /tasks/{id} request
 * sends a {@code version} that does not match the current row version in the database.
 *
 * <p>Maps to HTTP 409 Conflict in {@link GlobalExceptionHandler}.
 *
 * <p><b>What this means:</b> Between the time the client read the task (and got version N)
 * and the time the client sent the PATCH, another request updated the task (bumping version
 * to N+1). The client's changes would silently overwrite that intermediate update — so we
 * reject the request instead.
 *
 * <p><b>How the client should respond:</b> Re-fetch the task to get the latest version,
 * re-apply the intended changes, and retry the PATCH with the new version number.
 */
public class OptimisticLockConflictException extends RuntimeException {

    /**
     * @param taskId         the task whose version was stale
     * @param currentVersion the version currently stored in the DB
     * @param requestVersion the version the client sent (stale)
     */
    public OptimisticLockConflictException(UUID taskId, int currentVersion, int requestVersion) {
        super("Task " + taskId + " was modified concurrently. " +
              "Current version: " + currentVersion + ", your version: " + requestVersion +
              ". Fetch the latest version and retry.");
    }
}
