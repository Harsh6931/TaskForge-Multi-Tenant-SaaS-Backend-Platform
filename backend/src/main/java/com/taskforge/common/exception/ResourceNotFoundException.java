package com.taskforge.common.exception;

import java.util.UUID;

/**
 * ResourceNotFoundException — thrown when a requested entity does not exist
 * or has been soft-deleted (and is therefore invisible to normal queries).
 *
 * <p>Maps to HTTP 404 Not Found in {@link GlobalExceptionHandler}.
 *
 * <p>The message deliberately avoids leaking whether the resource was never
 * created vs. soft-deleted — both cases return the same 404 to the caller.
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * @param resourceType human-readable name of the entity type (e.g., "Task", "Project")
     * @param id           the UUID that was not found
     */
    public ResourceNotFoundException(String resourceType, UUID id) {
        super(resourceType + " not found: " + id);
    }

    /**
     * Overload for cases where no UUID is available (e.g., search by slug).
     *
     * @param message the full error message
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
