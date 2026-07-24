# Phase 3 — Core Domain: Projects & Tasks
### Implementation Breakdown (Small Learning Goals)

> **Current state (entering Phase 3):** Phase 2 is fully complete. We have:
> - Full JWT auth: signup, login, refresh, logout, tenant-switch
> - `JwtAuthenticationFilter` + `ApiKeyAuthenticationFilter` — two auth paths active
> - `@EnableMethodSecurity` on `SecurityConfig` — `@PreAuthorize` is live and ready
> - `GlobalExceptionHandler` — handles 400, 401, 403, 409 with RFC 7807 `ProblemDetail`
> - `Project` entity + `ProjectRepository` — skeleton from Phase 1, no service/controller yet
> - V2 migration: `projects`, `tasks`, `comments`, `labels`, `task_labels` tables fully created with indexes
> - RLS active on all tenant-scoped tables (V7)
> - `BaseEntity` providing `id`, `createdAt`, `updatedAt`, `deletedAt` + `SoftDeleteService`

> **Phase 3 Goal:** Build the full project and task domain — every entity, service, controller, and edge case that the API contract demands. By the end you will have: CRUD for projects/tasks/comments/labels, a task status state machine, optimistic concurrency control, cursor-based pagination, and full-text search.

---

## 🗂️ Overview Map

```
Goal 1 → Understand cursor pagination + optimistic locking (the mental model)
Goal 2 → Build Task, Comment, Label, TaskLabel entities + repositories
Goal 3 → Build ProjectService + ProjectController (full CRUD + @PreAuthorize)
Goal 4 → Build TaskService — CRUD + status machine + optimistic locking
Goal 5 → Build TaskController — REST layer + cursor pagination endpoint
Goal 6 → Build CommentService + CommentController
Goal 7 → Build LabelService + LabelController
Goal 8 → Implement full-text search (tsvector) on tasks
Goal 9 → Extend GlobalExceptionHandler for Phase 3 exceptions
Goal 10 → Write the full test suite
```

---

## Goal 1 — Understand Cursor Pagination & Optimistic Locking (Mental Model First)

> **Why first?** These are the two Phase 3 concepts most likely to be asked about in interviews. Both look simple but have subtle "gotcha" moments that separate copy-pasters from people who actually understand their system.

### Cursor-based Pagination

#### What's wrong with offset pagination?
```sql
-- Offset pagination (naïve):
SELECT * FROM tasks LIMIT 20 OFFSET 40;
-- Problem 1: If a row is inserted before offset 40, you skip one row on the next page
-- Problem 2: Postgres must scan and discard the first 40 rows on every call — gets slower as offset grows
-- Problem 3: Row ordering is unstable without an explicit ORDER BY on a unique column
```

#### How cursor pagination works
```sql
-- Cursor pagination (correct):
-- "Give me 20 tasks created AFTER the task with id = <cursor_id>"
SELECT * FROM tasks
WHERE (created_at, id) < (:cursor_created_at, :cursor_id)  -- composite cursor
  AND deleted_at IS NULL
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

- The **cursor** is an opaque token the server sends in `next_cursor`
- The client sends it back as `?cursor=<token>` on the next request
- Under the hood: the cursor encodes `(created_at, id)` of the last seen row
- The query then uses `(created_at, id) < (:prev_created_at, :prev_id)` — a **keyset seek** — which the DB can satisfy with an index scan, not a full-table skip

#### Interview talking point
> *"I use cursor-based pagination instead of LIMIT/OFFSET because offset pagination has O(n) scan cost and causes row skips when data changes during pagination. My cursor encodes the last seen `(created_at, id)` pair. The query uses a keyset comparison `(created_at, id) < (cursor_ts, cursor_id)` — the composite index covers this and the DB jumps directly to the next page without scanning discarded rows."*

### Optimistic Locking

#### The problem it solves
```
User A: GET /tasks/123  → receives { version: 5, title: "Fix bug" }
User B: GET /tasks/123  → receives { version: 5, title: "Fix bug" }

User A: PATCH /tasks/123 { title: "Fix critical bug", version: 5 }
User B: PATCH /tasks/123 { title: "Fix bug - assigned to John", version: 5 }
                                                               ↑ stale version!
```
Without locking, User B overwrites User A's change silently. This is a **lost update** — one of the most common concurrency bugs in web apps.

#### How optimistic locking fixes it
- Each row has a `version INTEGER DEFAULT 0`
- On every PATCH, the client sends back the `version` it last saw
- The service runs: `UPDATE tasks SET ..., version = version + 1 WHERE id = ? AND version = ?`
- If `0 rows updated` → the version didn't match → someone else updated it first → return **409 Conflict**
- No DB-level locks held — just a compare-and-swap in SQL

#### Interview talking point
> *"I use optimistic locking on the `tasks` table via a `version` integer column. On every PATCH, the client echoes back the version it read. My service runs `UPDATE ... WHERE id = ? AND version = ?` — if zero rows are updated, I return 409 Conflict. This is optimistic because we assume conflicts are rare and don't hold locks; we only detect them at write time. It's the same pattern Hibernate's `@Version` uses under the hood."*

---

## Goal 2 — Task, Comment, Label, TaskLabel Entities + Repositories

> **Why this before service?** Same pattern as Phase 1 and 2 — always build the data layer first, then the logic layer. Every service class will import these.

### 2a — Task Entity

```java
// com/taskforge/task/entity/Task.java
@Entity
@Table(name = "tasks")
@Where(clause = "deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Task extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "tenant_id", insertable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false,
            columnDefinition = "task_status")
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false,
            columnDefinition = "task_priority")
    private TaskPriority priority;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Column(name = "due_date")
    private LocalDate dueDate;

    // The optimistic lock version — Hibernate manages increment on every UPDATE
    // We also manage it manually in TaskService for explicit 409 control
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    // Labels — many-to-many via task_labels join table
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "task_labels",
        joinColumns = @JoinColumn(name = "task_id"),
        inverseJoinColumns = @JoinColumn(name = "label_id")
    )
    private Set<Label> labels = new HashSet<>();
}
```

**Key implementation notes:**
- `@Enumerated(EnumType.STRING)` with `columnDefinition = "task_status"` — tells Hibernate to use the PG enum type name from V2 migration; without `columnDefinition`, Hibernate defaults to `VARCHAR` and PG rejects it
- **Do NOT use `@Version`** on the `version` field — `@Version` makes Hibernate throw `OptimisticLockException` internally, which is harder to translate to a 409. We manage the compare-and-swap in SQL ourselves for explicit control.
- `labels` initialized to `new HashSet<>()` — prevents NPE when adding labels to a newly created task

### 2b — TaskStatus and TaskPriority Enums

```java
// com/taskforge/task/entity/TaskStatus.java
public enum TaskStatus {
    BACKLOG, IN_PROGRESS, DONE;

    // State machine — defines which transitions are valid
    // Called in TaskService to reject illegal transitions
    public boolean canTransitionTo(TaskStatus next) {
        return switch (this) {
            case BACKLOG     -> next == IN_PROGRESS;
            case IN_PROGRESS -> next == DONE;
            case DONE        -> next == IN_PROGRESS;  // reopen is allowed, DONE→BACKLOG is not
        };
    }
}

// com/taskforge/task/entity/TaskPriority.java
public enum TaskPriority { LOW, MEDIUM, HIGH }
```

> **Why put `canTransitionTo` on the enum?** The transition rules are a property of the status itself — they don't belong in the service. If you add a new status later, you update the enum, not hunt for a conditional buried in a service method. This is the **State pattern** applied to an enum.

### 2c — Comment Entity

```java
// com/taskforge/task/entity/Comment.java
@Entity
@Table(name = "comments")
@Where(clause = "deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Comment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "tenant_id", insertable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    private Task task;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User author;

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;
}
```

### 2d — Label Entity

```java
// com/taskforge/task/entity/Label.java
@Entity
@Table(name = "labels")
// No @Where — labels have no soft-delete per SCHEMA.md design decision
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Label {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "color", nullable = false, length = 50)
    private String color;
}
```

> **Why doesn't Label extend BaseEntity?** Because labels have no `deleted_at` — SCHEMA.md notes "completely delete label." BaseEntity adds `deletedAt` and `@Where(deleted_at IS NULL)`, which would conflict with the label's hard-delete design. Labels get their own lightweight entity instead.

### 2e — Repositories

```java
// com/taskforge/task/repository/TaskRepository.java
@Repository
public interface TaskRepository extends JpaRepository<Task, UUID> {

    // For cursor-based pagination (Goal 5) — see detailed JPQL in Goal 5
    // For status filtering + assignee filtering
    // Named queries defined here, custom @Query for cursor pagination

    long countByProjectId(UUID projectId);  // used by Phase 4 UsageGuard
}

// com/taskforge/task/repository/CommentRepository.java
@Repository
public interface CommentRepository extends JpaRepository<Comment, UUID> {
    List<Comment> findAllByTaskIdOrderByCreatedAtAsc(UUID taskId);
}

// com/taskforge/task/repository/LabelRepository.java
@Repository
public interface LabelRepository extends JpaRepository<Label, UUID> {
    List<Label> findAllByTenantId(UUID tenantId);
    boolean existsByIdAndTenantId(UUID id, UUID tenantId); // security check before attaching label
}
```

### Files to create
- `com/taskforge/task/entity/Task.java`
- `com/taskforge/task/entity/TaskStatus.java`
- `com/taskforge/task/entity/TaskPriority.java`
- `com/taskforge/task/entity/Comment.java`
- `com/taskforge/task/entity/Label.java`
- `com/taskforge/task/repository/TaskRepository.java`
- `com/taskforge/task/repository/CommentRepository.java`
- `com/taskforge/task/repository/LabelRepository.java`

---

## Goal 3 — ProjectService + ProjectController

> **Why build Project before Task?** Tasks belong to Projects. The `ProjectService` must exist (and be testable) before `TaskService` references it. Build dependencies bottom-up.

### 3a — DTOs

```java
// com/taskforge/project/dto/CreateProjectRequest.java
record CreateProjectRequest(
    @NotBlank @Size(max = 255) String name,
    String description  // optional
) {}

// com/taskforge/project/dto/UpdateProjectRequest.java
record UpdateProjectRequest(
    @Size(max = 255) String name,       // nullable = patch semantics
    String description
) {}

// com/taskforge/project/dto/ProjectResponse.java
record ProjectResponse(
    UUID id,
    String name,
    String description,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt
) {
    // Static factory method — keeps mapping logic out of the service
    public static ProjectResponse from(Project project) { ... }
}
```

### 3b — ProjectService

```java
// com/taskforge/project/ProjectService.java
@Service
@Transactional
@RequiredArgsConstructor
public class ProjectService {

    // createProject(UUID tenantId, UUID userId, CreateProjectRequest) → ProjectResponse
    //   1. Load Tenant by tenantId (throw ResourceNotFoundException if not found)
    //   2. Load User by userId (creator)
    //   3. Build + save Project entity (tenant, createdBy, name, description)
    //   4. Return ProjectResponse.from(project)
    //   Note: Phase 4 UsageGuard will check plan limits here before save

    // getProject(UUID projectId) → ProjectResponse
    //   1. findById — RLS ensures cross-tenant access is impossible
    //   2. Throw ResourceNotFoundException if not found (deleted_at filtered by @Where)
    //   3. Return ProjectResponse.from(project)

    // listProjects() → List<ProjectResponse>
    //   1. findAllByOrderByCreatedAtDesc()  ← RLS filters to current tenant automatically
    //   2. Map to ProjectResponse

    // updateProject(UUID projectId, UpdateProjectRequest) → ProjectResponse
    //   1. Load project (throw 404 if not found)
    //   2. Patch non-null fields only (PATCH semantics)
    //   3. Save and return updated response

    // deleteProject(UUID projectId)
    //   1. Load project (throw 404 if not found)
    //   2. softDeleteService.softDelete(project)  ← sets deletedAt, never hard DELETE
    //   3. No return value (controller returns 204)
}
```

### 3c — ProjectController

```java
// com/taskforge/project/ProjectController.java
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    // GET /projects
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public List<ProjectResponse> listProjects() { ... }

    // POST /projects
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ProjectResponse createProject(
        @Valid @RequestBody CreateProjectRequest request,
        @AuthenticationPrincipal UUID userId  // extracted from JWT by filter
    ) { ... }

    // GET /projects/{id}
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public ProjectResponse getProject(@PathVariable UUID id) { ... }

    // PATCH /projects/{id}
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ProjectResponse updateProject(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateProjectRequest request
    ) { ... }

    // DELETE /projects/{id}
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteProject(@PathVariable UUID id) { ... }
}
```

> **`@AuthenticationPrincipal UUID userId`** — works because `JwtAuthenticationFilter` sets `UsernamePasswordAuthenticationToken` with `principal = userId` (a UUID). Spring resolves `@AuthenticationPrincipal` directly from the `SecurityContext`. No `HttpSession`, no `ThreadLocal` lookup needed in the controller.

### Files to create
- `com/taskforge/project/dto/CreateProjectRequest.java`
- `com/taskforge/project/dto/UpdateProjectRequest.java`
- `com/taskforge/project/dto/ProjectResponse.java`
- `com/taskforge/project/ProjectService.java`
- `com/taskforge/project/ProjectController.java`

---

## Goal 4 — TaskService (Status Machine + Optimistic Locking)

> **Why the most complex goal?** TaskService has three non-trivial behaviors layered on top of CRUD: the status state machine, the optimistic locking compare-and-swap, and label attachment validation. These are the parts interviewers will probe.

### 4a — Task DTOs

```java
// com/taskforge/task/dto/CreateTaskRequest.java
record CreateTaskRequest(
    @NotBlank @Size(max = 255) String title,
    String description,
    @NotNull TaskPriority priority,
    UUID assigneeId,     // nullable
    LocalDate dueDate    // nullable
    // status defaults to BACKLOG on creation — not client-supplied
) {}

// com/taskforge/task/dto/UpdateTaskRequest.java
record UpdateTaskRequest(
    String title,
    String description,
    TaskStatus status,      // nullable — only present if client is transitioning status
    TaskPriority priority,
    UUID assigneeId,
    LocalDate dueDate,
    @NotNull Integer version  // REQUIRED — for optimistic lock check
) {}

// com/taskforge/task/dto/TaskResponse.java
record TaskResponse(
    UUID id,
    UUID projectId,
    String title,
    String description,
    TaskStatus status,
    TaskPriority priority,
    UUID assigneeId,
    LocalDate dueDate,
    Integer version,          // always returned so client can echo it back on PATCH
    List<LabelResponse> labels,
    Instant createdAt,
    Instant updatedAt
) {
    public static TaskResponse from(Task task) { ... }
}

// com/taskforge/task/dto/TaskPageResponse.java
record TaskPageResponse(
    List<TaskResponse> tasks,
    String nextCursor  // null if no more pages
) {}
```

### 4b — TaskService

```java
// com/taskforge/task/TaskService.java
@Service
@Transactional
@RequiredArgsConstructor
public class TaskService {

    // createTask(UUID projectId, UUID tenantId, UUID userId, CreateTaskRequest) → TaskResponse
    //   1. Load Project (throw 404 if not found — RLS ensures cross-tenant access blocked)
    //   2. Build Task: status = BACKLOG, version = 0, set all request fields
    //   3. Save task → return TaskResponse

    // getTask(UUID taskId) → TaskResponse
    //   1. findById (throw 404 if not found or soft-deleted)
    //   2. Return TaskResponse.from(task)

    // updateTask(UUID taskId, UpdateTaskRequest) → TaskResponse
    //   1. Load task (throw 404 if not found)
    //   2. ── VERSION CHECK (optimistic lock) ──
    //      if (!task.getVersion().equals(request.version())) {
    //          throw new OptimisticLockConflictException(taskId, task.getVersion(), request.version());
    //      }
    //   3. ── STATUS TRANSITION CHECK ──
    //      if (request.status() != null && request.status() != task.getStatus()) {
    //          if (!task.getStatus().canTransitionTo(request.status())) {
    //              throw new InvalidStatusTransitionException(task.getStatus(), request.status());
    //          }
    //          task.setStatus(request.status());
    //      }
    //   4. Patch other non-null fields (title, description, priority, assigneeId, dueDate)
    //   5. Increment version: task.setVersion(task.getVersion() + 1)
    //   6. Save and return TaskResponse

    // deleteTask(UUID taskId)
    //   1. Load task (throw 404 if not found)
    //   2. softDeleteService.softDelete(task)

    // addLabelToTask(UUID taskId, UUID labelId, UUID tenantId)
    //   1. Load task (throw 404)
    //   2. Verify labelId belongs to tenantId (throw 403/404 if foreign label)
    //      labelRepository.existsByIdAndTenantId(labelId, tenantId) — security gate
    //   3. Load label, add to task.getLabels()
    //   4. Save task

    // removeLabelFromTask(UUID taskId, UUID labelId)
    //   1. Load task (throw 404)
    //   2. task.getLabels().removeIf(l -> l.getId().equals(labelId))
    //   3. Save task
}
```

> **Why increment `version` manually instead of using `@Version`?**
> Hibernate's `@Version` throws an `OptimisticLockException` *after* the UPDATE executes — deep inside the JPA flush cycle. You have to catch it in a filter or AOP advice and translate to 409. Manual compare-and-swap lets you validate the version *before* the DB call, throw a named business exception, and let `GlobalExceptionHandler` map it cleanly. Total control, zero magic.

### Files to create
- `com/taskforge/task/dto/CreateTaskRequest.java`
- `com/taskforge/task/dto/UpdateTaskRequest.java`
- `com/taskforge/task/dto/TaskResponse.java`
- `com/taskforge/task/dto/TaskPageResponse.java`
- `com/taskforge/task/TaskService.java`

---

## Goal 5 — TaskController + Cursor-Based Pagination

> **Why a separate goal from TaskService?** The cursor pagination is implemented half in the repository (the JPQL query) and half in the controller (encoding/decoding the cursor). Keep this logic visible — it's the most technically interesting part of Phase 3.

### 5a — Cursor encoding/decoding

```java
// com/taskforge/task/util/CursorUtil.java
public class CursorUtil {

    // Cursor format: Base64( createdAt_epochMillis + ":" + taskId_uuid )
    // Example raw: "1721234567890:550e8400-e29b-41d4-a716-446655440000"
    // Encoded:      "MTcyMTIzNDU2Nzg5MDo1NTBlODQwMC1lMjliLTQxZDQtYTcxNi00NDY2NTU0NDAwMDA="

    public static String encode(Instant createdAt, UUID id) {
        String raw = createdAt.toEpochMilli() + ":" + id.toString();
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static CursorValues decode(String cursor) {
        String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String[] parts = raw.split(":", 2);
        return new CursorValues(
            Instant.ofEpochMilli(Long.parseLong(parts[0])),
            UUID.fromString(parts[1])
        );
    }

    public record CursorValues(Instant createdAt, UUID id) {}
}
```

### 5b — Cursor query in TaskRepository

```java
// com/taskforge/task/repository/TaskRepository.java — add this query:
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
    UUID projectId,
    TaskStatus status,
    UUID assigneeId,
    Instant cursorCreatedAt,
    UUID cursorId,
    Pageable pageable  // only the LIMIT part — we handle ordering ourselves
);

// First-page query (no cursor provided):
@Query("""
    SELECT t FROM Task t
    WHERE t.project.id = :projectId
      AND (:status IS NULL OR t.status = :status)
      AND (:assigneeId IS NULL OR t.assignee.id = :assigneeId)
    ORDER BY t.createdAt DESC, t.id DESC
    """)
List<Task> findTasksFirstPage(
    UUID projectId,
    TaskStatus status,
    UUID assigneeId,
    Pageable pageable
);
```

> **Why two queries instead of one?** The cursor condition `(createdAt, id) < (cursor_ts, cursor_id)` is a dynamic SQL fragment that JPQL can't express as a conditional. Two clean named queries are clearer and easier to test than one query with dynamic WHERE clauses or Criteria API complexity.

### 5c — Pagination logic in TaskService

```java
// Add to TaskService:
@Transactional(readOnly = true)
public TaskPageResponse listTasksForProject(
    UUID projectId, String cursor, int limit, TaskStatus status, UUID assigneeId
) {
    // Fetch limit+1 to detect if there is a next page
    Pageable pageable = PageRequest.of(0, limit + 1);

    List<Task> tasks;
    if (cursor == null) {
        tasks = taskRepository.findTasksFirstPage(projectId, status, assigneeId, pageable);
    } else {
        CursorUtil.CursorValues cv = CursorUtil.decode(cursor);
        tasks = taskRepository.findTasksAfterCursor(
            projectId, status, assigneeId, cv.createdAt(), cv.id(), pageable
        );
    }

    boolean hasMore = tasks.size() > limit;
    List<Task> page = hasMore ? tasks.subList(0, limit) : tasks;

    String nextCursor = hasMore
        ? CursorUtil.encode(page.getLast().getCreatedAt(), page.getLast().getId())
        : null;

    return new TaskPageResponse(page.stream().map(TaskResponse::from).toList(), nextCursor);
}
```

> **The limit+1 trick:** Fetching one extra row tells you whether a next page exists without a separate `COUNT(*)` query. If you get `limit+1` rows back, there's more data — truncate to `limit` and encode the last row as `next_cursor`. If you get ≤ `limit` rows, `next_cursor` is null. One DB round-trip instead of two.

### 5d — TaskController

```java
// com/taskforge/task/TaskController.java
@RestController
@RequiredArgsConstructor
public class TaskController {

    // GET /projects/{projectId}/tasks?cursor=&limit=20&status=&assignee=
    @GetMapping("/projects/{projectId}/tasks")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public TaskPageResponse listTasks(
        @PathVariable UUID projectId,
        @RequestParam(required = false) String cursor,
        @RequestParam(defaultValue = "20") @Max(100) int limit,
        @RequestParam(required = false) TaskStatus status,
        @RequestParam(required = false) UUID assignee
    ) { ... }

    // POST /projects/{projectId}/tasks
    @PostMapping("/projects/{projectId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public TaskResponse createTask(
        @PathVariable UUID projectId,
        @Valid @RequestBody CreateTaskRequest request,
        @AuthenticationPrincipal UUID userId
    ) { ... }

    // GET /tasks/{id}
    @GetMapping("/tasks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public TaskResponse getTask(@PathVariable UUID id) { ... }

    // PATCH /tasks/{id}
    @PatchMapping("/tasks/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public TaskResponse updateTask(
        @PathVariable UUID id,
        @Valid @RequestBody UpdateTaskRequest request
    ) { ... }

    // DELETE /tasks/{id}
    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public void deleteTask(@PathVariable UUID id) { ... }

    // POST /tasks/{id}/labels/{labelId}
    @PostMapping("/tasks/{id}/labels/{labelId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public TaskResponse addLabel(
        @PathVariable UUID id,
        @PathVariable UUID labelId,
        @AuthenticationPrincipal UUID userId  // to resolve tenantId from SecurityContext
    ) { ... }

    // DELETE /tasks/{id}/labels/{labelId}
    @DeleteMapping("/tasks/{id}/labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public void removeLabel(@PathVariable UUID id, @PathVariable UUID labelId) { ... }
}
```

### Files to create
- `com/taskforge/task/util/CursorUtil.java`
- `com/taskforge/task/TaskController.java`

### Files to modify
- `com/taskforge/task/repository/TaskRepository.java` — add paginated queries

---

## Goal 6 — CommentService + CommentController

> **Why separate from Task?** Comments have their own security rules: only the comment author (or ADMIN/MANAGER) can delete their comment. This is **resource ownership** checking — a different pattern from role checking.

### 6a — Comment DTOs

```java
// com/taskforge/task/dto/CreateCommentRequest.java
record CreateCommentRequest(@NotBlank String body) {}

// com/taskforge/task/dto/CommentResponse.java
record CommentResponse(
    UUID id,
    UUID taskId,
    UUID authorId,
    String body,
    Instant createdAt,
    Instant updatedAt
) {
    public static CommentResponse from(Comment comment) { ... }
}
```

### 6b — CommentService

```java
// com/taskforge/task/CommentService.java
@Service
@Transactional
@RequiredArgsConstructor
public class CommentService {

    // addComment(UUID taskId, UUID userId, UUID tenantId, CreateCommentRequest) → CommentResponse
    //   1. Load Task (throw 404 if not found) — RLS ensures cross-tenant impossible
    //   2. Load User (the commenter)
    //   3. Load Tenant
    //   4. Build + save Comment
    //   5. Return CommentResponse

    // listComments(UUID taskId) → List<CommentResponse>
    //   1. findAllByTaskIdOrderByCreatedAtAsc(taskId)
    //   2. Map to CommentResponse

    // deleteComment(UUID commentId, UUID requestingUserId, TenantUserRole requestingRole)
    //   1. Load Comment (throw 404 if not found)
    //   2. ── OWNERSHIP CHECK ──
    //      boolean isAuthor = comment.getAuthor().getId().equals(requestingUserId);
    //      boolean isPrivileged = role == ADMIN || role == MANAGER;
    //      if (!isAuthor && !isPrivileged) throw new AccessDeniedException(...)
    //   3. softDeleteService.softDelete(comment)
}
```

> **Why not use `@PreAuthorize` alone for comment delete?** `@PreAuthorize` can check roles but not resource ownership (whether the requesting user is the comment's author). This requires loading the comment from the DB first. The ownership check happens inside the service after the load — this is the standard pattern for resource-level authorization in Spring.

### 6c — CommentController

```java
// com/taskforge/task/CommentController.java
@RestController
@RequiredArgsConstructor
public class CommentController {

    // POST /tasks/{taskId}/comments
    @PostMapping("/tasks/{taskId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public CommentResponse addComment(
        @PathVariable UUID taskId,
        @Valid @RequestBody CreateCommentRequest request,
        @AuthenticationPrincipal UUID userId
    ) { ... }

    // GET /tasks/{taskId}/comments
    @GetMapping("/tasks/{taskId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public List<CommentResponse> listComments(@PathVariable UUID taskId) { ... }

    // DELETE /comments/{id}
    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public void deleteComment(
        @PathVariable UUID id,
        @AuthenticationPrincipal UUID userId
    ) { ... }
}
```

### Files to create
- `com/taskforge/task/dto/CreateCommentRequest.java`
- `com/taskforge/task/dto/CommentResponse.java`
- `com/taskforge/task/CommentService.java`
- `com/taskforge/task/CommentController.java`

---

## Goal 7 — LabelService + LabelController

> **Why simple?** Labels are the lightest entity — no soft-delete, no state machine, no pagination. This goal is intentionally a breather before the complexity of full-text search.

### 7a — Label DTOs

```java
// com/taskforge/task/dto/CreateLabelRequest.java
record CreateLabelRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$",
                       message = "Color must be a valid hex code e.g. #FF5733")
    String color
) {}

// com/taskforge/task/dto/LabelResponse.java
record LabelResponse(UUID id, String name, String color) {
    public static LabelResponse from(Label label) { ... }
}
```

### 7b — LabelService

```java
// com/taskforge/task/LabelService.java
@Service
@Transactional
@RequiredArgsConstructor
public class LabelService {

    // createLabel(UUID tenantId, CreateLabelRequest) → LabelResponse
    //   1. Build Label entity with tenantId, name, color
    //   2. Save + return LabelResponse

    // listLabels(UUID tenantId) → List<LabelResponse>
    //   1. findAllByTenantId(tenantId)
    //   2. Map to LabelResponse

    // deleteLabel(UUID labelId, UUID tenantId)
    //   1. Load label — verify it belongs to tenantId (throw 404/403 if mismatch)
    //   2. labelRepository.delete(label)  ← hard delete — labels have no soft-delete
}
```

### 7c — LabelController

```java
// com/taskforge/task/LabelController.java
@RestController
@RequestMapping("/labels")
@RequiredArgsConstructor
public class LabelController {

    // GET /labels
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public List<LabelResponse> listLabels(@AuthenticationPrincipal UUID userId) { ... }

    // POST /labels
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public LabelResponse createLabel(
        @Valid @RequestBody CreateLabelRequest request,
        @AuthenticationPrincipal UUID userId
    ) { ... }

    // DELETE /labels/{id}
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public void deleteLabel(@PathVariable UUID id, @AuthenticationPrincipal UUID userId) { ... }
}
```

### Files to create
- `com/taskforge/task/dto/CreateLabelRequest.java`
- `com/taskforge/task/dto/LabelResponse.java`
- `com/taskforge/task/LabelService.java`
- `com/taskforge/task/LabelController.java`

---

## Goal 8 — Full-Text Search with tsvector

> **Why important?** This is a real PostgreSQL feature used at scale. Knowing how to use `tsvector` + `GIN` index puts you above engineers who just do `LIKE '%query%'` searches.

### 8a — Flyway migration for tsvector column

Create `V8__add_task_fts.sql`:

```sql
-- Add a generated tsvector column that Postgres keeps automatically in sync
ALTER TABLE tasks
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (
            setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
            setweight(to_tsvector('english', coalesce(description, '')), 'B')
        ) STORED;

-- GIN index for fast full-text search on the generated column
CREATE INDEX idx_tasks_search_vector ON tasks USING GIN (search_vector);
```

> **Why `GENERATED ALWAYS AS ... STORED`?**
> The DB automatically recomputes `search_vector` whenever `title` or `description` changes — zero application-side maintenance. `STORED` means the value is physically written to disk (not computed on read), so the GIN index can be built over it. `setweight('A')` gives title matches a higher relevance ranking than `setweight('B')` description matches.

### 8b — Search query in TaskRepository

```java
// Add to TaskRepository:
@Query(value = """
    SELECT * FROM tasks
    WHERE tenant_id = CAST(:tenantId AS uuid)
      AND deleted_at IS NULL
      AND search_vector @@ plainto_tsquery('english', :query)
    ORDER BY ts_rank(search_vector, plainto_tsquery('english', :query)) DESC
    LIMIT :limit
    """, nativeQuery = true)
List<Task> searchByFullText(UUID tenantId, String query, int limit);
```

> **Why `nativeQuery = true` here?** The `@@` operator and `plainto_tsquery()` / `ts_rank()` are PostgreSQL-specific. JPQL has no equivalent — this is one of the few places where a native query is the correct tool. The tenant_id filter is inline (not relying solely on RLS) because native queries bypass the `@Where` annotation — always add an explicit `WHERE tenant_id = ?` in native queries.

### 8c — SearchController

```java
// com/taskforge/task/SearchController.java
@RestController
@RequiredArgsConstructor
public class SearchController {

    // GET /tasks/search?q=fix+the+login+bug&limit=10
    @GetMapping("/tasks/search")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public List<TaskResponse> search(
        @RequestParam @NotBlank String q,
        @RequestParam(defaultValue = "10") @Max(50) int limit,
        @AuthenticationPrincipal UUID userId   // used to resolve tenantId from SecurityContext
    ) { ... }
}
```

> **Why limit 50 max on search?** Full-text search results are ranked by relevance. Returning more than 50 results is usually noise — and the GIN index is fast but not infinitely scalable for huge result sets. Enforce a cap at the API layer.

### Files to create
- `backend/src/main/resources/db/migration/V8__add_task_fts.sql`
- `com/taskforge/task/SearchController.java`

### Files to modify
- `com/taskforge/task/repository/TaskRepository.java` — add `searchByFullText` native query

---

## Goal 9 — Extend GlobalExceptionHandler

> **Why a dedicated goal?** Phase 3 introduces several new business exception types. Wire them into the handler before writing tests — tests that expect 404/409/422 will fail if the handler doesn't know about these exceptions yet.

### New exception classes

```java
// com/taskforge/common/exception/ResourceNotFoundException.java
// Used when: project not found, task not found, label not found, comment not found
// HTTP status: 404 Not Found
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resourceType, UUID id) {
        super(resourceType + " not found: " + id);
    }
}

// com/taskforge/common/exception/InvalidStatusTransitionException.java
// Used when: task.status.canTransitionTo(newStatus) returns false
// HTTP status: 422 Unprocessable Entity
public class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException(TaskStatus from, TaskStatus to) {
        super("Cannot transition task from " + from + " to " + to);
    }
}

// com/taskforge/common/exception/OptimisticLockConflictException.java
// Used when: task version in request != current version in DB
// HTTP status: 409 Conflict
public class OptimisticLockConflictException extends RuntimeException {
    public OptimisticLockConflictException(UUID taskId, int currentVersion, int requestVersion) {
        super("Task " + taskId + " was modified by another request. " +
              "Current version: " + currentVersion + ", your version: " + requestVersion);
    }
}
```

### Handler additions

```java
// Add to GlobalExceptionHandler.java:

// ── 404 Not Found ──────────────────────────────────────────────────────────
@ExceptionHandler(ResourceNotFoundException.class)
public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
    log.warn("Resource not found: {}", ex.getMessage());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    problem.setTitle("Resource Not Found");
    return problem;
}

// ── 409 Conflict — Optimistic Lock ──────────────────────────────────────────
@ExceptionHandler(OptimisticLockConflictException.class)
public ProblemDetail handleOptimisticLock(OptimisticLockConflictException ex) {
    log.warn("Optimistic lock conflict: {}", ex.getMessage());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    problem.setTitle("Concurrent Modification Conflict");
    problem.setProperty("hint", "Fetch the latest version and retry.");
    return problem;
}

// ── 422 Unprocessable Entity — Invalid Status Transition ────────────────────
@ExceptionHandler(InvalidStatusTransitionException.class)
public ProblemDetail handleInvalidTransition(InvalidStatusTransitionException ex) {
    log.warn("Invalid status transition: {}", ex.getMessage());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    problem.setTitle("Invalid Status Transition");
    return problem;
}
```

### Files to create
- `com/taskforge/common/exception/ResourceNotFoundException.java`
- `com/taskforge/common/exception/InvalidStatusTransitionException.java`
- `com/taskforge/common/exception/OptimisticLockConflictException.java`

### Files to modify
- `com/taskforge/common/exception/GlobalExceptionHandler.java` — add three handlers above

---

## Goal 10 — Test Suite

> **Phase 3 deliverable:** Every service method with business logic has a unit test. Every controller endpoint has at least one integration test. You'll be able to run `grep -rc "@Test" src/test/` and get a meaningful number.

### 10a — ProjectServiceTest (Unit Test)

```java
// src/test/java/com/taskforge/project/ProjectServiceTest.java
// @ExtendWith(MockitoExtension.class) — no Spring context

// Test cases:
// createProject_success_returnsProjectResponse()
// createProject_tenantNotFound_throws404()
// getProject_notFound_throws404()
// updateProject_patchesNonNullFieldsOnly()
// deleteProject_softDeletesProject()
```

### 10b — TaskServiceTest (Unit Test — most important)

```java
// src/test/java/com/taskforge/task/TaskServiceTest.java

// Test cases:
// createTask_success_defaultsStatusToBacklog()
// updateTask_versionMismatch_throws409()
// updateTask_versionMatch_incrementsVersion()
// updateTask_validStatusTransition_BACKLOG_to_IN_PROGRESS_succeeds()
// updateTask_invalidStatusTransition_DONE_to_BACKLOG_throws422()
// updateTask_validReopenTransition_IN_PROGRESS_from_DONE_succeeds()
// addLabel_labelBelongsToTenant_success()
// addLabel_labelFromOtherTenant_throws404()
// deleteTask_softDeletesTask()
```

### 10c — TaskControllerTest (Integration Test / MockMvc)

```java
// src/test/java/com/taskforge/task/TaskControllerTest.java
// @WebMvcTest — tests HTTP layer, mocks service layer

// Test cases:
// GET /projects/{id}/tasks → 200 with page response
// GET /projects/{id}/tasks?cursor=<encoded> → 200 with next page
// POST /projects/{id}/tasks → 201 with created task
// POST /projects/{id}/tasks with VIEWER role → 403
// PATCH /tasks/{id} with correct version → 200
// PATCH /tasks/{id} with stale version → 409
// PATCH /tasks/{id} with invalid status transition → 422
// DELETE /tasks/{id} with MEMBER role → 403
// GET /tasks/search?q=login → 200 with results
```

### 10d — CommentServiceTest (Unit Test)

```java
// src/test/java/com/taskforge/task/CommentServiceTest.java

// Test cases:
// addComment_success_returnsCommentResponse()
// deleteComment_byAuthor_succeeds()
// deleteComment_byAdmin_succeeds()
// deleteComment_byNonAuthorMember_throws403()
```

### 10e — CursorUtilTest (Unit Test — pure logic)

```java
// src/test/java/com/taskforge/task/CursorUtilTest.java

// Test cases:
// encode_decode_roundTripProducesOriginalValues()
// decode_invalidCursor_throwsIllegalArgumentException()
// encode_producesUrlSafeBase64()
```

### Files to create
- `src/test/java/com/taskforge/project/ProjectServiceTest.java`
- `src/test/java/com/taskforge/task/TaskServiceTest.java`
- `src/test/java/com/taskforge/task/TaskControllerTest.java`
- `src/test/java/com/taskforge/task/CommentServiceTest.java`
- `src/test/java/com/taskforge/task/CursorUtilTest.java`

---

## Phase 3 Completion Checklist

| Goal | Description | Status |
|---|---|---|
| Goal 1 | Cursor pagination + optimistic locking mental model internalized | ⬜ |
| Goal 2 | Task, Comment, Label, TaskLabel entities + repositories | ⬜ |
| Goal 3 | ProjectService + ProjectController (full CRUD + @PreAuthorize) | ⬜ |
| Goal 4 | TaskService — CRUD + status machine + optimistic locking | ⬜ |
| Goal 5 | TaskController + cursor-based pagination endpoint | ⬜ |
| Goal 6 | CommentService + CommentController (with ownership check) | ⬜ |
| Goal 7 | LabelService + LabelController | ⬜ |
| Goal 8 | V8 migration (tsvector) + full-text search endpoint | ⬜ |
| Goal 9 | GlobalExceptionHandler extended (404, 409, 422) | ⬜ |
| Goal 10 | Full test suite — unit + integration + cursor tests | ⬜ |

---

## Package Structure After Phase 3

```
com.taskforge
├── TaskForgeApplication.java
├── common/
│   ├── BaseEntity.java
│   ├── SoftDeleteService.java
│   └── exception/
│       ├── EmailAlreadyExistsException.java
│       ├── InvalidCredentialsException.java
│       ├── InvalidTokenException.java
│       ├── TenantAccessDeniedException.java
│       ├── ResourceNotFoundException.java          ← NEW
│       ├── InvalidStatusTransitionException.java   ← NEW
│       ├── OptimisticLockConflictException.java    ← NEW
│       └── GlobalExceptionHandler.java             ← MODIFIED
├── config/
│   ├── JpaConfig.java
│   ├── JwtProperties.java
│   ├── SecurityConfig.java
│   └── TenantConfig.java
├── auth/                              (Phase 2 — unchanged)
│   └── ...
├── tenant/                            (Phase 1 — unchanged)
│   └── ...
├── user/                              (Phase 1 — unchanged)
│   └── ...
├── project/                           ← EXPANDED
│   ├── dto/
│   │   ├── CreateProjectRequest.java  ← NEW
│   │   ├── UpdateProjectRequest.java  ← NEW
│   │   └── ProjectResponse.java       ← NEW
│   ├── entity/
│   │   └── Project.java               (Phase 1 — unchanged)
│   ├── repository/
│   │   └── ProjectRepository.java     (Phase 1 — unchanged)
│   ├── ProjectService.java            ← NEW
│   └── ProjectController.java         ← NEW
└── task/                              ← NEW PACKAGE
    ├── dto/
    │   ├── CreateTaskRequest.java
    │   ├── UpdateTaskRequest.java
    │   ├── TaskResponse.java
    │   ├── TaskPageResponse.java
    │   ├── CreateCommentRequest.java
    │   ├── CommentResponse.java
    │   ├── CreateLabelRequest.java
    │   └── LabelResponse.java
    ├── entity/
    │   ├── Task.java
    │   ├── TaskStatus.java
    │   ├── TaskPriority.java
    │   ├── Comment.java
    │   └── Label.java
    ├── repository/
    │   ├── TaskRepository.java
    │   ├── CommentRepository.java
    │   └── LabelRepository.java
    ├── util/
    │   └── CursorUtil.java
    ├── TaskService.java
    ├── TaskController.java
    ├── CommentService.java
    ├── CommentController.java
    ├── LabelService.java
    ├── LabelController.java
    └── SearchController.java
```

---

## Key Concepts Mastered After Phase 3

| Concept | Where you used it |
|---|---|
| Task status state machine | `TaskStatus.canTransitionTo()` + `TaskService.updateTask()` |
| Optimistic locking (manual compare-and-swap) | `TaskService.updateTask()` → `OptimisticLockConflictException` |
| Cursor-based pagination (keyset seek) | `CursorUtil` + `TaskRepository` JPQL + `TaskService.listTasksForProject()` |
| limit+1 trick for has-next detection | `TaskService.listTasksForProject()` |
| PostgreSQL `tsvector` GENERATED ALWAYS | `V8__add_task_fts.sql` |
| GIN index for full-text search | `V8__add_task_fts.sql` |
| `plainto_tsquery` + `ts_rank` | `TaskRepository.searchByFullText()` native query |
| Resource ownership authorization | `CommentService.deleteComment()` — role OR author check |
| `@AuthenticationPrincipal` in controllers | `ProjectController`, `TaskController`, `CommentController` |
| PATCH semantics (null-skipping partial update) | `ProjectService.updateProject()`, `TaskService.updateTask()` |
| RFC 7807 ProblemDetail for 404/409/422 | `GlobalExceptionHandler` additions |
| `@Enumerated(EnumType.STRING)` with PG enum | `Task.status`, `Task.priority` |
| Native `@Query` for PG-specific operators | `TaskRepository.searchByFullText()` |
| `@ManyToMany` with join table | `Task.labels` ↔ `Label` via `task_labels` |

---

## 📏 Measurement Checkpoint (Phase 3 — collect after tests go green)

Per `docs/METRICS_PLAYBOOK.md` § Metric 3.x:

1. **Total `@Test` methods:**
   ```powershell
   Select-String -Path "backend\src\test\**\*.java" -Pattern "@Test" -Recurse | Measure-Object
   ```
   Record in § Metric 3.1

2. **REST endpoint count:**
   ```powershell
   Select-String -Path "backend\src\main\**\*.java" -Pattern "@(Get|Post|Put|Patch|Delete)Mapping" -Recurse | Measure-Object
   ```
   Record in § Metric 3.2

3. **Verify FTS migration ran:**
   ```sql
   SELECT column_name, generation_expression
   FROM information_schema.columns
   WHERE table_name = 'tasks' AND column_name = 'search_vector';
   ```

4. **Verify GIN index exists:**
   ```sql
   SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'tasks';
   ```
