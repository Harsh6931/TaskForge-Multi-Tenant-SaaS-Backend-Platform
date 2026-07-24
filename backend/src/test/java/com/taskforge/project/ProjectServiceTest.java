package com.taskforge.project;

import com.taskforge.common.SoftDeleteService;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.project.dto.ProjectResponse;
import com.taskforge.project.dto.UpdateProjectRequest;
import com.taskforge.project.entity.Project;
import com.taskforge.project.repository.ProjectRepository;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.tenant.repository.TenantRepository;
import com.taskforge.user.entity.User;
import com.taskforge.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ProjectServiceTest — unit tests for {@link ProjectService}.
 *
 * <p>Uses Mockito only — no Spring context, no DB. Tests the business logic
 * of the service in complete isolation.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock ProjectRepository  projectRepository;
    @Mock TenantRepository   tenantRepository;
    @Mock UserRepository     userRepository;
    @Mock SoftDeleteService  softDeleteService;

    @InjectMocks ProjectService projectService;

    private UUID tenantId;
    private UUID userId;
    private Tenant tenant;
    private User   user;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        userId   = UUID.randomUUID();

        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setName("Acme Corp");
        tenant.setSlug("acme");

        user = new User();
        user.setId(userId);
        user.setEmail("alice@acme.com");
        user.setFullName("Alice");
        user.setPasswordHash("hashed");
    }

    // ── createProject ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("createProject: success — returns ProjectResponse with correct fields")
    void createProject_success_returnsProjectResponse() {
        CreateProjectRequest request = new CreateProjectRequest("Backend API", "Our API project");

        Project saved = Project.builder()
                .tenant(tenant)
                .createdBy(user)
                .name("Backend API")
                .description("Our API project")
                .build();
        saved.setId(UUID.randomUUID());

        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(projectRepository.save(any(Project.class))).thenReturn(saved);

        ProjectResponse response = projectService.createProject(tenantId, userId, request);

        assertThat(response.name()).isEqualTo("Backend API");
        assertThat(response.description()).isEqualTo("Our API project");
        assertThat(response.createdById()).isEqualTo(userId);
        verify(projectRepository).save(any(Project.class));
    }

    @Test
    @DisplayName("createProject: tenant not found — throws ResourceNotFoundException")
    void createProject_tenantNotFound_throws404() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.createProject(
                tenantId, userId, new CreateProjectRequest("Name", null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tenant");

        verify(projectRepository, never()).save(any());
    }

    // ── getProject ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getProject: not found — throws ResourceNotFoundException")
    void getProject_notFound_throws404() {
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getProject(projectId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project");
    }

    // ── listProjects ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("listProjects: delegates to repository and maps to DTOs")
    void listProjects_returnsMappedDtos() {
        Project p1 = Project.builder().tenant(tenant).createdBy(user).name("P1").build();
        p1.setId(UUID.randomUUID());
        Project p2 = Project.builder().tenant(tenant).createdBy(user).name("P2").build();
        p2.setId(UUID.randomUUID());

        when(projectRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(p1, p2));

        List<ProjectResponse> result = projectService.listProjects();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("P1");
        assertThat(result.get(1).name()).isEqualTo("P2");
    }

    // ── updateProject ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateProject: patches only non-null fields (PATCH semantics)")
    void updateProject_patchesNonNullFieldsOnly() {
        UUID projectId = UUID.randomUUID();
        Project existing = Project.builder()
                .tenant(tenant)
                .createdBy(user)
                .name("Old Name")
                .description("Old Desc")
                .build();
        existing.setId(projectId);

        // Only updating name, description stays the same
        UpdateProjectRequest request = new UpdateProjectRequest("New Name", null);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(existing));
        when(projectRepository.save(existing)).thenReturn(existing);

        ProjectResponse response = projectService.updateProject(projectId, request);

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.description()).isEqualTo("Old Desc"); // untouched
    }

    // ── deleteProject ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteProject: calls softDeleteService with the loaded project")
    void deleteProject_softDeletesProject() {
        UUID projectId = UUID.randomUUID();
        Project existing = Project.builder().tenant(tenant).createdBy(user).name("ToDelete").build();
        existing.setId(projectId);

        when(projectRepository.findById(projectId)).thenReturn(Optional.of(existing));

        projectService.deleteProject(projectId);

        verify(softDeleteService).softDelete(existing, projectRepository);
    }
}
