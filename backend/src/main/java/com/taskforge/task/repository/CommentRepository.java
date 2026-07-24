package com.taskforge.task.repository;

import com.taskforge.task.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * CommentRepository — data access for the {@link Comment} entity.
 *
 * <p>RLS on the {@code comments} table (V7 migration) scopes every query to the
 * current tenant automatically. The {@code @Where(clause = "deleted_at IS NULL")}
 * on {@link Comment} filters soft-deleted comments from all derived queries.
 */
@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {

    /**
     * Returns all active comments for a task, ordered oldest-first.
     * Used by {@code GET /tasks/{taskId}/comments}.
     *
     * @param taskId the task whose comments to retrieve
     */
    List<Comment> findAllByTaskIdOrderByCreatedAtAsc(UUID taskId);
}
