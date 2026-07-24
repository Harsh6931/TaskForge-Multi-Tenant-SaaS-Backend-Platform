package com.taskforge.task;

import com.taskforge.common.SoftDeleteService;
import com.taskforge.common.exception.OptimisticLockConflictException;
import com.taskforge.common.exception.InvalidStatusTransitionException;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.project.entity.Project;
import com.taskforge.project.repository.ProjectRepository;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.TaskPageResponse;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.task.entity.Label;
import com.taskforge.task.entity.Task;
import com.taskforge.task.entity.TaskStatus;
import com.taskforge.task.repository.LabelRepository;
import com.taskforge.task.repository.TaskRepository;
import com.taskforge.task.util.CursorUtil;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.tenant.repository.TenantRepository;
import com.taskforge.user.entity.User;
import com.taskforge.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * TaskService — business logic for task CRUD, status machine, and optimistic locking.
 *
 * <p><b>State machine:</b>
 * Valid status transitions are encoded on {@link TaskStatus#canTransitionTo}.
 * The service enforces them in {@link #updateTask} — invalid transitions throw
 * {@link InvalidStatusTransitionException} (HTTP 422).
 *
 * <p><b>Optimistic locking:</b>
 * The {@code version} field is a manual compare-and-swap, NOT Hibernate's {@code @Version}.
 * On every PATCH, the client must echo back the version it last read. If the stored version
 * has advanced (another request updated the task in between), we throw
 * {@link OptimisticLockConflictException} (HTTP 409) before touching the DB.
 *
 * <p><b>Cursor-based pagination:</b>
 * {@link #listTasksForProject} uses the limit+1 trick: fetching {@code limit + 1} rows
 * detects whether a next page exists without a separate COUNT query. The last row of the
 * current page is encoded as the next cursor.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class TaskService {

    private final TaskRepository    taskRepository;
    private final ProjectRepository projectRepository;
    private final TenantRepository  tenantRepository;
    private final UserRepository    userRepository;
    private final LabelRepository   labelRepository;
    private final SoftDeleteService softDeleteService;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new task in a project. All tasks start as {@code BACKLOG}.
     *
     * @param projectId the project to create the task in
     * @param tenantId  the current tenant (from JWT)
     * @param request   validated task fields
     * @return the created task as a response DTO
     * @throws ResourceNotFoundException if the project or assignee doesn't exist
     */
    public TaskResponse createTask(UUID projectId, UUID tenantId, CreateTaskRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        User assignee = null;
        if (request.assigneeId() != null) {
            assignee = userRepository.findById(request.assigneeId())
                    .orElseThrow(() -> new ResourceNotFoundException("User (assignee)", request.assigneeId()));
        }

        Task task = Task.builder()
                .tenant(tenant)
                .project(project)
                .title(request.title())
                .description(request.description())
                .status(TaskStatus.BACKLOG)
                .priority(request.priority())
                .assignee(assignee)
                .dueDate(request.dueDate())
                .build();

        task = taskRepository.save(task);
        log.info("Task created: taskId={} projectId={} tenantId={}", task.getId(), projectId, tenantId);

        return TaskResponse.from(task);
    }

    // ── Read (single) ─────────────────────────────────────────────────────────

    /**
     * Returns a single task by its ID.
     * RLS scopes the query to the current tenant automatically.
     *
     * @param taskId the task UUID
     * @return the task response DTO
     * @throws ResourceNotFoundException if no active task with that ID exists
     */
    @Transactional(readOnly = true)
    public TaskResponse getTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
        return TaskResponse.from(task);
    }

    // ── Read (paginated list) ─────────────────────────────────────────────────

    /**
     * Returns a cursor-paginated page of tasks for a project.
     *
     * <p><b>Algorithm:</b>
     * <ol>
     *   <li>Fetch {@code limit + 1} rows (the "limit+1 trick").</li>
     *   <li>If we got {@code limit + 1} results, there is a next page —
     *       truncate to {@code limit} and encode the last row as the next cursor.</li>
     *   <li>If we got {@code ≤ limit} results, we're on the last page —
     *       {@code nextCursor} is {@code null}.</li>
     * </ol>
     *
     * @param projectId  the project to list tasks for
     * @param cursor     opaque cursor from the previous page, or {@code null} for the first page
     * @param limit      maximum tasks per page (enforced at controller layer)
     * @param status     optional status filter
     * @param assigneeId optional assignee filter
     * @return paginated task list with optional next cursor
     */
    @Transactional(readOnly = true)
    public TaskPageResponse listTasksForProject(
            UUID projectId,
            String cursor,
            int limit,
            TaskStatus status,
            UUID assigneeId
    ) {
        // Fetch one extra to detect whether a next page exists
        Pageable pageable = PageRequest.of(0, limit + 1);

        List<Task> tasks;
        if (cursor == null) {
            tasks = taskRepository.findTasksFirstPage(projectId, status, assigneeId, pageable);
        } else {
            CursorUtil.CursorValues cv = CursorUtil.decode(cursor);
            tasks = taskRepository.findTasksAfterCursor(
                    projectId, status, assigneeId, cv.createdAt(), cv.id(), pageable);
        }

        boolean hasMore = tasks.size() > limit;
        List<Task> page = hasMore ? tasks.subList(0, limit) : tasks;

        String nextCursor = hasMore
                ? CursorUtil.encode(page.getLast().getCreatedAt(), page.getLast().getId())
                : null;

        return new TaskPageResponse(
                page.stream().map(TaskResponse::from).toList(),
                nextCursor
        );
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Applies a partial update to a task, enforcing the status machine and optimistic lock.
     *
     * <p><b>Order of checks:</b>
     * <ol>
     *   <li>Load task (throws 404 if not found)</li>
     *   <li>Version check — throw 409 if stale</li>
     *   <li>Status transition check — throw 422 if invalid</li>
     *   <li>Apply non-null field patches</li>
     *   <li>Increment version manually</li>
     *   <li>Save</li>
     * </ol>
     *
     * @param taskId  the task to update
     * @param request the patch payload including the mandatory {@code version}
     * @return the updated task response DTO
     * @throws ResourceNotFoundException      if no active task with that ID exists
     * @throws OptimisticLockConflictException if the client's version is stale (409)
     * @throws InvalidStatusTransitionException if the requested status transition is invalid (422)
     */
    public TaskResponse updateTask(UUID taskId, UpdateTaskRequest request) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));

        // ── Optimistic lock check ──────────────────────────────────────────────
        if (!task.getVersion().equals(request.version())) {
            throw new OptimisticLockConflictException(taskId, task.getVersion(), request.version());
        }

        // ── Status transition check ────────────────────────────────────────────
        if (request.status() != null && request.status() != task.getStatus()) {
            if (!task.getStatus().canTransitionTo(request.status())) {
                throw new InvalidStatusTransitionException(task.getStatus(), request.status());
            }
            task.setStatus(request.status());
        }

        // ── Patch non-null fields ──────────────────────────────────────────────
        if (request.title() != null) {
            task.setTitle(request.title());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        if (request.dueDate() != null) {
            task.setDueDate(request.dueDate());
        }
        if (request.assigneeId() != null) {
            User assignee = userRepository.findById(request.assigneeId())
                    .orElseThrow(() -> new ResourceNotFoundException("User (assignee)", request.assigneeId()));
            task.setAssignee(assignee);
        }

        // ── Increment version ──────────────────────────────────────────────────
        task.setVersion(task.getVersion() + 1);

        task = taskRepository.save(task);
        log.info("Task updated: taskId={} newVersion={} status={}", taskId, task.getVersion(), task.getStatus());

        return TaskResponse.from(task);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Soft-deletes a task. The task's comments remain in the DB but are also
     * invisible to normal queries (their own {@code @Where} filter applies).
     *
     * @param taskId the task to delete
     * @throws ResourceNotFoundException if no active task with that ID exists
     */
    public void deleteTask(UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
        softDeleteService.softDelete(task, taskRepository);
        log.info("Task soft-deleted: taskId={}", taskId);
    }

    // ── Label management ──────────────────────────────────────────────────────

    /**
     * Attaches a label to a task.
     *
     * <p><b>Security gate:</b> Verifies the label belongs to the same tenant as the task
     * before attaching. This prevents tenant A from adding tenant B's labels to their tasks.
     *
     * @param taskId   the task to add the label to
     * @param labelId  the label to attach
     * @param tenantId the current tenant (from JWT, used to verify label ownership)
     * @return the updated task response DTO
     * @throws ResourceNotFoundException if the task or label doesn't exist in this tenant
     */
    public TaskResponse addLabelToTask(UUID taskId, UUID labelId, UUID tenantId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));

        // Security gate: label must belong to the same tenant
        if (!labelRepository.existsByIdAndTenantId(labelId, tenantId)) {
            throw new ResourceNotFoundException("Label", labelId);
        }

        Label label = labelRepository.findById(labelId)
                .orElseThrow(() -> new ResourceNotFoundException("Label", labelId));

        task.getLabels().add(label);
        task = taskRepository.save(task);

        log.info("Label {} added to task {}", labelId, taskId);
        return TaskResponse.from(task);
    }

    /**
     * Detaches a label from a task. No-op if the label is not currently attached.
     *
     * @param taskId  the task to remove the label from
     * @param labelId the label to detach
     * @throws ResourceNotFoundException if no active task with that ID exists
     */
    public void removeLabelFromTask(UUID taskId, UUID labelId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));

        task.getLabels().removeIf(l -> l.getId().equals(labelId));
        taskRepository.save(task);

        log.info("Label {} removed from task {}", labelId, taskId);
    }

    // ── Full-text search ──────────────────────────────────────────────────────

    /**
     * Searches tasks within the current tenant using PostgreSQL full-text search.
     * Results are ranked by relevance (title matches outrank description matches).
     *
     * @param tenantId the current tenant UUID
     * @param query    the raw search string
     * @param limit    maximum number of results (enforced at controller layer)
     * @return list of matching tasks ordered by relevance
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> searchTasks(UUID tenantId, String query, int limit) {
        return taskRepository.searchByFullText(tenantId, query, limit)
                .stream()
                .map(TaskResponse::from)
                .toList();
    }
}
