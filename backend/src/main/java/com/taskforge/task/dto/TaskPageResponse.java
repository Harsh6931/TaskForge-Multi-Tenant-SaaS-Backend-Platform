package com.taskforge.task.dto;

import java.util.List;

/**
 * TaskPageResponse — the paginated response for {@code GET /projects/{id}/tasks}.
 *
 * <p>{@code nextCursor} is {@code null} when there are no more pages.
 * The client should keep calling with {@code ?cursor=<nextCursor>} until
 * {@code nextCursor} is absent from the response.
 *
 * @param tasks      the current page of tasks (at most {@code limit} items)
 * @param nextCursor opaque Base64 cursor to fetch the next page, or {@code null}
 */
public record TaskPageResponse(
    List<TaskResponse> tasks,
    String nextCursor
) {}
