package com.taskforge.task;

import com.taskforge.common.SoftDeleteService;
import com.taskforge.common.exception.CommentAccessDeniedException;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.task.dto.CommentResponse;
import com.taskforge.task.dto.CreateCommentRequest;
import com.taskforge.task.entity.Comment;
import com.taskforge.task.entity.Task;
import com.taskforge.task.repository.CommentRepository;
import com.taskforge.task.repository.TaskRepository;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.tenant.repository.TenantRepository;
import com.taskforge.user.entity.TenantUserRole;
import com.taskforge.user.entity.User;
import com.taskforge.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CommentService — business logic for comment CRUD operations.
 *
 * <p><b>Resource ownership authorization:</b>
 * Comment deletion is protected by a dual check: the user must be either the
 * comment's author OR a privileged role (ADMIN or MANAGER). This cannot be
 * expressed with {@code @PreAuthorize} alone (which only knows about roles, not
 * resource ownership), so the check is done in {@link #deleteComment} after
 * loading the comment entity.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CommentService {

    private final CommentRepository commentRepository;
    private final TaskRepository    taskRepository;
    private final TenantRepository  tenantRepository;
    private final UserRepository    userRepository;
    private final SoftDeleteService softDeleteService;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Adds a comment to a task.
     *
     * @param taskId   the task to comment on
     * @param userId   the authenticated user posting the comment
     * @param tenantId the current tenant (from JWT)
     * @param request  the comment body
     * @return the created comment as a response DTO
     * @throws ResourceNotFoundException if the task, user, or tenant doesn't exist
     */
    public CommentResponse addComment(
            UUID taskId,
            UUID userId,
            UUID tenantId,
            CreateCommentRequest request
    ) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));

        Comment comment = Comment.builder()
                .task(task)
                .author(author)
                .tenant(tenant)
                .body(request.body())
                .build();

        comment = commentRepository.save(comment);
        log.info("Comment added: commentId={} taskId={} authorId={}", comment.getId(), taskId, userId);

        return CommentResponse.from(comment);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Returns all active comments for a task, ordered oldest-first.
     * RLS scopes the query to the current tenant automatically.
     *
     * @param taskId the task whose comments to list
     * @return list of comment response DTOs
     */
    @Transactional(readOnly = true)
    public List<CommentResponse> listComments(UUID taskId) {
        return commentRepository.findAllByTaskIdOrderByCreatedAtAsc(taskId)
                .stream()
                .map(CommentResponse::from)
                .toList();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Soft-deletes a comment.
     *
     * <p><b>Authorization logic (resource ownership):</b>
     * Deletion is allowed if the requesting user is:
     * <ul>
     *   <li>The comment's author, OR</li>
     *   <li>An ADMIN or MANAGER in the current tenant</li>
     * </ul>
     *
     * <p>This check cannot be done with {@code @PreAuthorize} alone because it requires
     * loading the comment to know who the author is. The role check here mirrors what
     * the controller's {@code @PreAuthorize("hasAnyRole('ADMIN','MANAGER','MEMBER')")}
     * already enforces — we additionally require authorship for MEMBERs.
     *
     * @param commentId      the comment to delete
     * @param requestingUserId  the authenticated user's ID
     * @param requestingRole the authenticated user's role in the current tenant
     * @throws ResourceNotFoundException      if the comment doesn't exist
     * @throws CommentAccessDeniedException   if the user is not the author and not privileged
     */
    public void deleteComment(UUID commentId, UUID requestingUserId, TenantUserRole requestingRole) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));

        boolean isAuthor     = comment.getAuthor().getId().equals(requestingUserId);
        boolean isPrivileged = requestingRole == TenantUserRole.ADMIN
                            || requestingRole == TenantUserRole.MANAGER;

        if (!isAuthor && !isPrivileged) {
            throw new CommentAccessDeniedException(commentId, requestingUserId);
        }

        softDeleteService.softDelete(comment, commentRepository);
        log.info("Comment soft-deleted: commentId={} deletedBy={}", commentId, requestingUserId);
    }
}
