package com.taskforge.common.exception;

import com.taskforge.task.entity.TaskStatus;

/**
 * InvalidStatusTransitionException — thrown when a PATCH request tries to move
 * a task to a status that is not reachable from its current status.
 *
 * <p>Maps to HTTP 422 Unprocessable Entity in {@link GlobalExceptionHandler}.
 *
 * <p>Examples of invalid transitions (enforced by {@link TaskStatus#canTransitionTo}):
 * <ul>
 *   <li>DONE → BACKLOG (not allowed — must reopen to IN_PROGRESS first)</li>
 *   <li>BACKLOG → DONE (not allowed — must go through IN_PROGRESS)</li>
 * </ul>
 */
public class InvalidStatusTransitionException extends RuntimeException {

    /**
     * @param from the task's current status
     * @param to   the disallowed target status
     */
    public InvalidStatusTransitionException(TaskStatus from, TaskStatus to) {
        super("Invalid status transition: " + from + " → " + to +
              ". Allowed transitions: BACKLOG→IN_PROGRESS, IN_PROGRESS→DONE, DONE→IN_PROGRESS");
    }
}
