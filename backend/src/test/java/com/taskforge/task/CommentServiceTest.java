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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CommentServiceTest — unit tests for {@link CommentService}.
 *
 * <p>Focuses on the resource-ownership delete logic which cannot be tested
 * via {@code @PreAuthorize} alone.
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock CommentRepository commentRepository;
    @Mock TaskRepository    taskRepository;
    @Mock TenantRepository  tenantRepository;
    @Mock UserRepository    userRepository;
    @Mock SoftDeleteService softDeleteService;

    @InjectMocks CommentService commentService;

    private UUID tenantId;
    private UUID authorId;
    private UUID otherId;
    private Tenant tenant;
    private User author;
    private Task task;
    private Comment comment;

    @BeforeEach
    void setUp() {
        tenantId  = UUID.randomUUID();
        authorId  = UUID.randomUUID();
        otherId   = UUID.randomUUID();

        tenant = new Tenant();
        tenant.setId(tenantId);
        tenant.setSlug("acme");
        tenant.setName("Acme");

        author = new User();
        author.setId(authorId);
        author.setEmail("author@test.com");
        author.setFullName("Author");
        author.setPasswordHash("x");

        task = new Task();
        task.setId(UUID.randomUUID());

        comment = Comment.builder()
                .task(task)
                .author(author)
                .tenant(tenant)
                .body("This is a comment")
                .build();
        comment.setId(UUID.randomUUID());
    }

    // ── addComment ────────────────────────────────────────────────────────

    @Test
    @DisplayName("addComment: success — saves and returns CommentResponse")
    void addComment_success_returnsCommentResponse() {
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(userRepository.findById(authorId)).thenReturn(Optional.of(author));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(commentRepository.save(any(Comment.class))).thenReturn(comment);

        CommentResponse response = commentService.addComment(
                task.getId(), authorId, tenantId, new CreateCommentRequest("This is a comment"));

        assertThat(response.body()).isEqualTo("This is a comment");
        assertThat(response.authorId()).isEqualTo(authorId);
        verify(commentRepository).save(any(Comment.class));
    }

    @Test
    @DisplayName("addComment: task not found — throws ResourceNotFoundException")
    void addComment_taskNotFound_throws404() {
        when(taskRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.addComment(
                UUID.randomUUID(), authorId, tenantId, new CreateCommentRequest("body")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Task");
    }

    // ── deleteComment ───────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteComment: by author — succeeds even if MEMBER role")
    void deleteComment_byAuthor_succeeds() {
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));

        assertThatNoException().isThrownBy(() ->
                commentService.deleteComment(comment.getId(), authorId, TenantUserRole.MEMBER));

        verify(softDeleteService).softDelete(eq(comment), any());
    }

    @Test
    @DisplayName("deleteComment: by ADMIN (not author) — succeeds")
    void deleteComment_byAdmin_succeeds() {
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));

        // otherId is not the author but has ADMIN role
        assertThatNoException().isThrownBy(() ->
                commentService.deleteComment(comment.getId(), otherId, TenantUserRole.ADMIN));

        verify(softDeleteService).softDelete(eq(comment), any());
    }

    @Test
    @DisplayName("deleteComment: by MANAGER (not author) — succeeds")
    void deleteComment_byManager_succeeds() {
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));

        assertThatNoException().isThrownBy(() ->
                commentService.deleteComment(comment.getId(), otherId, TenantUserRole.MANAGER));
    }

    @Test
    @DisplayName("deleteComment: by MEMBER who is not the author — throws 403")
    void deleteComment_byNonAuthorMember_throws403() {
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));

        assertThatThrownBy(() ->
                commentService.deleteComment(comment.getId(), otherId, TenantUserRole.MEMBER))
                .isInstanceOf(CommentAccessDeniedException.class);

        verify(softDeleteService, never()).softDelete(any(), any());
    }
}
