package com.taskforge.task.dto;

import com.taskforge.task.entity.Comment;

import java.time.Instant;
import java.util.UUID;

/**
 * CommentResponse — the JSON shape returned by comment endpoints.
 *
 * @param id        the comment's UUID
 * @param taskId    the task this comment belongs to
 * @param authorId  UUID of the user who wrote the comment
 * @param body      the comment text
 * @param createdAt UTC creation timestamp
 * @param updatedAt UTC last-modified timestamp
 */
public record CommentResponse(
    UUID id,
    UUID taskId,
    UUID authorId,
    String body,
    Instant createdAt,
    Instant updatedAt
) {

    /**
     * Maps a {@link Comment} entity to a {@link CommentResponse} DTO.
     *
     * @param comment the comment entity
     * @return a new CommentResponse
     */
    public static CommentResponse from(Comment comment) {
        return new CommentResponse(
            comment.getId(),
            comment.getTask().getId(),
            comment.getAuthor().getId(),
            comment.getBody(),
            comment.getCreatedAt(),
            comment.getUpdatedAt()
        );
    }
}
