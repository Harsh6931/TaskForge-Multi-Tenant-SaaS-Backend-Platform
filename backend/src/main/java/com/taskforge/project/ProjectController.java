package com.taskforge.project;

import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.project.dto.ProjectResponse;
import com.taskforge.project.dto.UpdateProjectRequest;
import com.taskforge.tenant.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * ProjectController — REST layer for {@code /projects} endpoints.
 *
 * <p><b>Design principle:</b> This controller is intentionally thin — it translates
 * HTTP concerns (path variables, request bodies, status codes) into service calls.
 * All business logic lives in {@link ProjectService}.
 *
 * <p><b>RBAC via @PreAuthorize:</b>
 * Role checks use Spring Security's method-level security, enabled by
 * {@code @EnableMethodSecurity} in SecurityConfig. Roles are sourced from the JWT
 * access token claim and stored in the SecurityContext by JwtAuthenticationFilter.
 *
 * <p><b>@AuthenticationPrincipal UUID userId:</b>
 * Works because JwtAuthenticationFilter sets {@code UsernamePasswordAuthenticationToken}
 * with {@code principal = userId (UUID)}. Spring resolves this annotation directly
 * from the SecurityContext — no HttpSession or extra ThreadLocal needed.
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    // ── GET /projects ─────────────────────────────────────────────────────────

    /**
     * Lists all active projects for the current tenant.
     * Open to all authenticated roles — even VIEWERs can see the project list.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public List<ProjectResponse> listProjects() {
        return projectService.listProjects();
    }

    // ── POST /projects ────────────────────────────────────────────────────────

    /**
     * Creates a new project within the current tenant.
     * Restricted to ADMIN and MANAGER roles (per RBAC matrix in docs/RBAC.md).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ProjectResponse createProject(
            @Valid @RequestBody CreateProjectRequest request,
            @AuthenticationPrincipal UUID userId
    ) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return projectService.createProject(tenantId, userId, request);
    }

    // ── GET /projects/{id} ────────────────────────────────────────────────────

    /**
     * Returns a single project by its UUID.
     * RLS ensures the project belongs to the current tenant even without an explicit check.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public ProjectResponse getProject(@PathVariable UUID id) {
        return projectService.getProject(id);
    }

    // ── PATCH /projects/{id} ──────────────────────────────────────────────────

    /**
     * Partially updates a project (PATCH semantics — only non-null fields are applied).
     * Restricted to ADMIN and MANAGER roles.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ProjectResponse updateProject(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        return projectService.updateProject(id, request);
    }

    // ── DELETE /projects/{id} ─────────────────────────────────────────────────

    /**
     * Soft-deletes a project (sets {@code deleted_at}).
     * Restricted to ADMIN only — project deletion is a destructive, irreversible action
     * from the user's perspective (though the data is recoverable by nulling deleted_at).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteProject(@PathVariable UUID id) {
        projectService.deleteProject(id);
    }
}
