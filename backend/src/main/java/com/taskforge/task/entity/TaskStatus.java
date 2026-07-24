package com.taskforge.task.entity;

/**
 * TaskStatus — the state machine for a task's lifecycle.
 *
 * <p><b>Valid transitions:</b>
 * <pre>
 *   BACKLOG → IN_PROGRESS   (start work)
 *   IN_PROGRESS → DONE      (complete)
 *   DONE → IN_PROGRESS      (reopen)
 *   DONE → BACKLOG          ❌ not allowed (use explicit reopen, then move to backlog manually)
 *   BACKLOG → DONE          ❌ not allowed (must go through IN_PROGRESS)
 * </pre>
 *
 * <p><b>Why encode transitions on the enum?</b>
 * The transition rules are a property of the status itself — not the service.
 * If a new status is added later, the enum is the single place to update.
 * This is the State pattern applied to an enum — no if/else chains in the service.
 */
public enum TaskStatus {

    /** Task is queued but not yet started. */
    BACKLOG,

    /** Task is actively being worked on. */
    IN_PROGRESS,

    /** Task is completed. Can be reopened to IN_PROGRESS. */
    DONE;

    /**
     * Returns true if transitioning from {@code this} status to {@code next} is allowed.
     *
     * @param next the target status
     * @return true if the transition is valid, false otherwise
     */
    public boolean canTransitionTo(TaskStatus next) {
        return switch (this) {
            case BACKLOG     -> next == IN_PROGRESS;
            case IN_PROGRESS -> next == DONE;
            case DONE        -> next == IN_PROGRESS;  // reopen allowed; DONE→BACKLOG is not
        };
    }
}
