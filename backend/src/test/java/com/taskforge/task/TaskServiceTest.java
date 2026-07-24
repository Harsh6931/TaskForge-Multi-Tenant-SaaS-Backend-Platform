package com.taskforge.task;

import com.taskforge.common.SoftDeleteService;
import com.taskforge.common.exception.InvalidStatusTransitionException;
import com.taskforge.common.exception.OptimisticLockConflictException;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.project.entity.Project;
import com.taskforge.project.repository.ProjectRepository;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.task.entity.Task;
import com.taskforge.task.entity.TaskPriority;
import com.taskforge.task.entity.TaskStatus;
import com.taskforge.task.repository.LabelRepository;
import com.taskforge.task.repository.TaskRepository;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.tenant.repository.TenantRepository;
import com.taskforge.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TaskServiceTest — unit tests for {@link TaskService}.
 *
 * <p>Covers the most critical business logic:
 * <ul>
 *   <li>Optimistic lock version mismatch → 409</li>
 *   <li>Invalid status transitions → 422</li>
 *   <li>Valid status transitions pass through</li>
 *   <li>BACKLOG default on creation</li>
 *   <li>Version increment on every PATCH</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock TaskRepository    taskRepository;
    @Mock ProjectRepository projectRepository;
    @Mock TenantRepository  tenantRepository;
    @Mock UserRepository    userRepository;
    @Mock LabelRepository   labelRepository;
    @Mock SoftDeleteService softDeleteService;

    @InjectMocks TaskService taskService;

    private UUID tenantId;
    private UUID projectId;
    private Tenant tenant;
    private Project project;

    @BeforeEach
    void setUp() {
        tenantId  = UUID.randomUUID();
        projectId = UUID.randomUUID();

        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Acme");
        tenant.setSlug("acme");

        project = Project.builder()
                .tenant(tenant)
                .name("Test Project")
                .build();
        project.setId(projectId);
    }

    // Helper: create a task in a given status with a given version
    private Task buildTask(TaskStatus status, int version) {
        Task task = Task.builder()
                .tenant(tenant)
                .project(project)
                .title("Sample Task")
                .status(status)
                .priority(TaskPriority.MEDIUM)
                .build();
        task.setId(UUID.randomUUID());
        task.setVersion(version);
        return task;
    }

    // ── createTask ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("createTask: new task always starts with BACKLOG status")
    void createTask_success_defaultsStatusToBacklog() {
        CreateTaskRequest request = new CreateTaskRequest(
                "Fix login bug", "Details", TaskPriority.HIGH, null, null);

        Task saved = buildTask(TaskStatus.BACKLOG, 0);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(taskRepository.save(any(Task.class))).thenReturn(saved);

        TaskResponse response = taskService.createTask(projectId, tenantId, request);

        assertThat(response.status()).isEqualTo(TaskStatus.BACKLOG);
        assertThat(response.version()).isEqualTo(0);
    }

    // ── updateTask — optimistic lock ──────────────────────────────────────────

    @Test
    @DisplayName("updateTask: version mismatch — throws OptimisticLockConflictException (409)")
    void updateTask_versionMismatch_throws409() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(TaskStatus.BACKLOG, 3); // stored version = 3
        task.setId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        // Client sends version 2 (stale)
        UpdateTaskRequest request = new UpdateTaskRequest(
                null, null, null, null, null, null, 2);

        assertThatThrownBy(() -> taskService.updateTask(taskId, request))
                .isInstanceOf(OptimisticLockConflictException.class)
                .hasMessageContaining("version");

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateTask: correct version — increments version on save")
    void updateTask_versionMatch_incrementsVersion() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(TaskStatus.BACKLOG, 0);
        task.setId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        UpdateTaskRequest request = new UpdateTaskRequest(
                "Updated Title", null, null, null, null, null, 0);

        TaskResponse response = taskService.updateTask(taskId, request);

        assertThat(response.version()).isEqualTo(1); // incremented
        assertThat(response.title()).isEqualTo("Updated Title");
    }

    // ── updateTask — status machine ───────────────────────────────────────────

    @Test
    @DisplayName("updateTask: BACKLOG → IN_PROGRESS — valid transition succeeds")
    void updateTask_validTransition_BACKLOG_to_IN_PROGRESS_succeeds() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(TaskStatus.BACKLOG, 0);
        task.setId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        UpdateTaskRequest request = new UpdateTaskRequest(
                null, null, TaskStatus.IN_PROGRESS, null, null, null, 0);

        TaskResponse response = taskService.updateTask(taskId, request);

        assertThat(response.status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("updateTask: DONE → IN_PROGRESS (reopen) — valid transition succeeds")
    void updateTask_validReopenTransition_IN_PROGRESS_from_DONE_succeeds() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(TaskStatus.DONE, 2);
        task.setId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.save(task)).thenReturn(task);

        UpdateTaskRequest request = new UpdateTaskRequest(
                null, null, TaskStatus.IN_PROGRESS, null, null, null, 2);

        TaskResponse response = taskService.updateTask(taskId, request);
        assertThat(response.status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("updateTask: DONE → BACKLOG — invalid transition throws 422")
    void updateTask_invalidTransition_DONE_to_BACKLOG_throws422() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(TaskStatus.DONE, 1);
        task.setId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        UpdateTaskRequest request = new UpdateTaskRequest(
                null, null, TaskStatus.BACKLOG, null, null, null, 1);

        assertThatThrownBy(() -> taskService.updateTask(taskId, request))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("DONE")
                .hasMessageContaining("BACKLOG");

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateTask: BACKLOG → DONE — invalid transition throws 422")
    void updateTask_invalidTransition_BACKLOG_to_DONE_throws422() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(TaskStatus.BACKLOG, 0);
        task.setId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        UpdateTaskRequest request = new UpdateTaskRequest(
                null, null, TaskStatus.DONE, null, null, null, 0);

        assertThatThrownBy(() -> taskService.updateTask(taskId, request))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    // ── addLabelToTask ──────────────────────────────────────────────────────

    @Test
    @DisplayName("addLabel: label from different tenant — throws ResourceNotFoundException")
    void addLabel_labelFromOtherTenant_throws404() {
        UUID taskId  = UUID.randomUUID();
        UUID labelId = UUID.randomUUID();
        Task task    = buildTask(TaskStatus.BACKLOG, 0);
        task.setId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        // Label does NOT belong to this tenant
        when(labelRepository.existsByIdAndTenantId(labelId, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> taskService.addLabelToTask(taskId, labelId, tenantId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Label");
    }

    // ── deleteTask ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteTask: calls softDeleteService with the task")
    void deleteTask_softDeletesTask() {
        UUID taskId = UUID.randomUUID();
        Task task   = buildTask(TaskStatus.IN_PROGRESS, 2);
        task.setId(taskId);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskService.deleteTask(taskId);

        verify(softDeleteService).softDelete(task, taskRepository);
    }
}
