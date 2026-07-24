package com.taskforge.task.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * CreateCommentRequest — validated request body for {@code POST /tasks/{id}/comments}.
 *
 * @param body the comment text (required, may contain markdown)
 */
public record CreateCommentRequest(
    @NotBlank(message = "Comment body must not be blank")
    String body
) {}
