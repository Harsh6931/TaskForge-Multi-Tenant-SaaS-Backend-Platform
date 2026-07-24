package com.taskforge.task;

import com.taskforge.task.dto.CommentResponse;
import com.taskforge.task.dto.CreateCommentRequest;
import com.taskforge.tenant.TenantContextHolder;
import com.taskforge.user.entity.TenantUserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CommentController — REST layer for comment endpoints.
 *
 * <p><b>Ownership-based delete:</b>
 * DELETE /comments/{id} passes the requesting user's ID and resolved role to
 * {@link CommentService#deleteComment} which enforces the author-or-privileged check.
 * The role is extracted from the SecurityContext's granted authorities.
 */
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    // ── POST /tasks/{taskId}/comments ────────────────────────────────────────────

    /**
     * Adds a comment to a task. VIEWER cannot comment (read-only role).
     */
    @PostMapping("/tasks/{taskId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public CommentResponse addComment(
            @PathVariable UUID taskId,
            @Valid @RequestBody CreateCommentRequest request,
            @AuthenticationPrincipal UUID userId
    ) {
        UUID tenantId = TenantContextHolder.getTenantId();
        return commentService.addComment(taskId, userId, tenantId, request);
    }

    // ── GET /tasks/{taskId}/comments ───────────────────────────────────────────

    /**
     * Lists all comments for a task, ordered oldest-first.
     * All roles including VIEWER can read comments.
     */
    @GetMapping("/tasks/{taskId}/comments")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public List<CommentResponse> listComments(@PathVariable UUID taskId) {
        return commentService.listComments(taskId);
    }

    // ── DELETE /comments/{id} ───────────────────────────────────────────────────

    /**
     * Soft-deletes a comment.
     * The actual author-or-privileged check is enforced in {@link CommentService#deleteComment}.
     * The role is resolved from the SecurityContext's first granted authority.
     */
    @DeleteMapping("/comments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER')")
    public void deleteComment(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            Authentication authentication
    ) {
        // Resolve role from SecurityContext authorities (set by JwtAuthenticationFilter)
        TenantUserRole role = authentication.getAuthorities().stream()
                .filter(a -> a instanceof SimpleGrantedAuthority)
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .map(TenantUserRole::valueOf)
                .findFirst()
                .orElse(TenantUserRole.VIEWER);

        commentService.deleteComment(id, userId, role);
    }
}
