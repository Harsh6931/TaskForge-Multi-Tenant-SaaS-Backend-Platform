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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * ProjectService — business logic for project CRUD operations.
 *
 * <p><b>Tenant isolation:</b>
 * All queries delegate to {@link ProjectRepository}, which is automatically scoped
 * to the current tenant via RLS. No explicit {@code WHERE tenant_id = ?} is needed
 * in this service — the database enforces isolation transparently.
 *
 * <p><b>Transactional strategy:</b>
 * Class-level {@code @Transactional} wraps all public methods by default.
 * Read-only methods are annotated with {@code @Transactional(readOnly = true)} to
 * skip Hibernate's dirty-checking overhead.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository  projectRepository;
    private final TenantRepository   tenantRepository;
    private final UserRepository     userRepository;
    private final SoftDeleteService  softDeleteService;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Creates a new project for the current tenant.
     *
     * <p>Phase 4 will add a UsageGuard call here to enforce the plan's
     * {@code max_projects} limit before the save occurs.
     *
     * @param tenantId the current tenant (from JWT claim)
     * @param userId   the authenticated user who is creating the project
     * @param request  validated project name and description
     * @return the persisted project as a response DTO
     */
    public ProjectResponse createProject(UUID tenantId, UUID userId, CreateProjectRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        User creator = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Project project = Project.builder()
                .tenant(tenant)
                .createdBy(creator)
                .name(request.name())
                .description(request.description())
                .build();

        project = projectRepository.save(project);
        log.info("Project created: projectId={} tenantId={} createdBy={}", project.getId(), tenantId, userId);

        return ProjectResponse.from(project);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns all active projects for the current tenant, newest first.
     * RLS scopes the query to the current tenant automatically.
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects() {
        return projectRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    /**
     * Returns a single project by its ID.
     * RLS ensures cross-tenant access is impossible even without an explicit tenant filter.
     *
     * @param projectId the project UUID
     * @return the project response DTO
     * @throws ResourceNotFoundException if no active project with that ID exists in this tenant
     */
    @Transactional(readOnly = true)
    public ProjectResponse getProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        return ProjectResponse.from(project);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /**
     * Applies a partial update (PATCH semantics) to a project.
     * Only non-null fields in the request are applied; null fields are left unchanged.
     *
     * @param projectId the project to update
     * @param request   the fields to update (both optional)
     * @return the updated project response DTO
     * @throws ResourceNotFoundException if no active project with that ID exists
     */
    public ProjectResponse updateProject(UUID projectId, UpdateProjectRequest request) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        if (request.name() != null) {
            project.setName(request.name());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }

        project = projectRepository.save(project);
        log.info("Project updated: projectId={}", projectId);

        return ProjectResponse.from(project);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Soft-deletes a project (sets {@code deleted_at}) rather than issuing a hard DELETE.
     *
     * <p>After soft-deletion the project becomes invisible to all JPA queries due to
     * {@code @Where(clause = "deleted_at IS NULL")} on the entity. Tasks and comments
     * that reference the project are also hidden because their own {@code @Where} filter
     * still applies; however their rows remain intact in the DB for audit purposes.
     *
     * @param projectId the project to delete
     * @throws ResourceNotFoundException if no active project with that ID exists
     */
    public void deleteProject(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

        softDeleteService.softDelete(project, projectRepository);
        log.info("Project soft-deleted: projectId={}", projectId);
    }
}
