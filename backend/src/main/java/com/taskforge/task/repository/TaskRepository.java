package com.taskforge.task.repository;

import com.taskforge.task.entity.Task;
import com.taskforge.task.entity.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * TaskRepository — data access for the {@link Task} entity.
 *
 * <p><b>RLS does the tenant filtering:</b>
 * Every query here is automatically scoped to the current tenant because the
 * TenantConnectionInterceptor runs {@code SET LOCAL app.current_tenant_id = ?}
 * before SQL executes, and the RLS policy on {@code tasks} filters rows accordingly.
 *
 * <p><b>Two pagination queries:</b>
 * Cursor-based pagination requires two separate queries:
 * <ol>
 *   <li>{@link #findTasksFirstPage} — no cursor condition, returns the most recent tasks</li>
 *   <li>{@link #findTasksAfterCursor} — keyset seek using {@code (createdAt, id)} composite</li>
 * </ol>
 * Two clean named queries are clearer than one query with dynamic WHERE fragments.
 *
 * <p><b>Native search query:</b>
 * {@link #searchByFullText} uses a native PostgreSQL query because {@code @@} (tsvector match)
 * and {@code plainto_tsquery} / {@code ts_rank} are PG-specific — JPQL has no equivalent.
 * The {@code tenant_id} filter is explicit (not relying solely on RLS) because native queries
 * bypass the {@code @Where} annotation.
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {

    /**
     * First-page query — returns the most recent tasks for a project, with optional filters.
     * No cursor condition; ordered by {@code (createdAt DESC, id DESC)} for stable ordering.
     *
     * @param projectId  the project to list tasks for (RLS further scopes to current tenant)
     * @param status     optional status filter; {@code null} means all statuses
     * @param assigneeId optional assignee filter; {@code null} means all assignees
     * @param pageable   use {@code PageRequest.of(0, limit+1)} — we fetch one extra to detect
     *                   whether a next page exists (the limit+1 trick)
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.project.id = :projectId
          AND (:status IS NULL OR t.status = :status)
          AND (:assigneeId IS NULL OR t.assignee.id = :assigneeId)
        ORDER BY t.createdAt DESC, t.id DESC
        """)
    List<Task> findTasksFirstPage(
        @Param("projectId") UUID projectId,
        @Param("status") TaskStatus status,
        @Param("assigneeId") UUID assigneeId,
        Pageable pageable
    );

    /**
     * Cursor page query — keyset seek using composite {@code (createdAt, id)} cursor.
     *
     * <p>The WHERE condition {@code (createdAt < cursorTs) OR (createdAt = cursorTs AND id < cursorId)}
     * is the keyset seek pattern. Combined with the composite index
     * {@code idx_tasks_tenant_project} (V2 migration), this avoids a full-table scan.
     *
     * @param projectId      the project to page through
     * @param status         optional status filter
     * @param assigneeId     optional assignee filter
     * @param cursorCreatedAt createdAt of the last task on the previous page
     * @param cursorId       id of the last task on the previous page
     * @param pageable       use {@code PageRequest.of(0, limit+1)}
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.project.id = :projectId
          AND (:status IS NULL OR t.status = :status)
          AND (:assigneeId IS NULL OR t.assignee.id = :assigneeId)
          AND (t.createdAt < :cursorCreatedAt
               OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId))
        ORDER BY t.createdAt DESC, t.id DESC
        """)
    List<Task> findTasksAfterCursor(
        @Param("projectId") UUID projectId,
        @Param("status") TaskStatus status,
        @Param("assigneeId") UUID assigneeId,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorId") UUID cursorId,
        Pageable pageable
    );

    /**
     * Full-text search using PostgreSQL's {@code tsvector} / GIN index.
     *
     * <p>Uses {@code plainto_tsquery} (simpler than {@code to_tsquery}) — converts
     * the search string to a tsquery without requiring explicit operators. Results
     * are ranked by {@code ts_rank} so the most relevant tasks appear first.
     *
     * <p><b>Why nativeQuery = true?</b>
     * The {@code @@} operator and tsquery functions are PostgreSQL-specific.
     * JPQL has no equivalent. Also note: native queries bypass the {@code @Where}
     * annotation so {@code deleted_at IS NULL} and {@code tenant_id = ?} filters
     * are added explicitly in the SQL.
     *
     * @param tenantId the current tenant (explicit filter — native query bypasses RLS @Where)
     * @param query    the raw search string from the user
     * @param limit    maximum number of results to return
     */
    @Query(value = """
        SELECT * FROM tasks
        WHERE tenant_id = CAST(:tenantId AS uuid)
          AND deleted_at IS NULL
          AND search_vector @@ plainto_tsquery('english', :query)
        ORDER BY ts_rank(search_vector, plainto_tsquery('english', :query)) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Task> searchByFullText(
        @Param("tenantId") UUID tenantId,
        @Param("query") String query,
        @Param("limit") int limit
    );

    /**
     * Counts active (non-deleted) tasks in a project.
     * Used by Phase 4 UsageGuard to enforce per-plan task limits.
     */
    long countByProjectId(UUID projectId);
}
