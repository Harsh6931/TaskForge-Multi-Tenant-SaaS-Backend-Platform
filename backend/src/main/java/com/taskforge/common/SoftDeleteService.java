package com.taskforge.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * SoftDeleteService — centralised helper for performing soft-deletes on any entity
 * that extends {@link BaseEntity}.
 *
 * <p><b>Why not just call {@code repository.delete()}?</b>
 * Calling {@code delete()} issues a SQL {@code DELETE}, which physically removes the row.
 * We never do this for business data because:
 * <ol>
 *   <li><b>Audit trail</b> — deleted data is still referenced by {@code audit_logs}.</li>
 *   <li><b>Recovery</b> — a mistaken delete can be reversed by nulling {@code deleted_at}.</li>
 *   <li><b>RLS safety</b> — RLS policies reference existing rows; hard-deleted rows
 *       cannot be "un-leaked" if RLS was momentarily bypassed.</li>
 *   <li><b>Referential integrity</b> — Comments, Tasks etc. reference Project IDs. A hard
 *       delete would cascade or leave dangling FKs depending on the constraint definition.</li>
 * </ol>
 *
 * <p><b>How to use:</b>
 * <pre>{@code
 *   // In a service method:
 *   @Transactional
 *   public void deleteProject(UUID id) {
 *       Project project = projectRepository.findById(id)
 *           .orElseThrow(() -> new EntityNotFoundException("Project not found"));
 *       softDeleteService.softDelete(project, projectRepository);
 *   }
 * }</pre>
 *
 * <p><b>What happens after soft-delete:</b>
 * The {@code @Where(clause = "deleted_at IS NULL")} on the entity class causes all
 * subsequent JPA queries ({@code findById}, {@code findAll}, etc.) to silently exclude
 * the soft-deleted row — it becomes invisible to the application layer without any
 * extra filtering.
 *
 * <p><b>Exception to soft-delete — {@code audit_logs}:</b>
 * Audit logs are append-only and are NEVER deleted (soft or hard). Do not use this
 * service for audit log entries.
 */
@Slf4j
@Service
public class SoftDeleteService {

    /**
     * Marks the given entity as deleted by setting its {@code deletedAt} timestamp
     * to the current UTC instant and persisting the change.
     *
     * <p>The entity must be managed within the current JPA persistence context
     * (i.e., retrieved via a repository in the same transaction). The {@code @Transactional}
     * annotation here ensures the save is wrapped in a transaction even if the caller
     * doesn't have one active.
     *
     * @param entity     the entity to soft-delete — must not already be soft-deleted
     * @param repository the repository used to persist the change
     * @param <T>        any entity type extending {@link BaseEntity}
     */
    @Transactional
    public <T extends BaseEntity> void softDelete(T entity, JpaRepository<T, UUID> repository) { // Work for All entitiies , no duplication
        if (entity.getDeletedAt() != null) {
            log.warn("softDelete called on already-deleted entity: id={}, type={}",
                    entity.getId(), entity.getClass().getSimpleName());
            return; // idempotent — no-op if already deleted
        }

        entity.setDeletedAt(Instant.now());
        repository.save(entity);

        log.info("Soft-deleted entity: id={}, type={}, deletedAt={}",
                entity.getId(), entity.getClass().getSimpleName(), entity.getDeletedAt());
    }

    /**
     * Restores a previously soft-deleted entity by nulling its {@code deletedAt} field.
     *
     * <p><b>Important:</b> After restoration, the entity becomes visible to all JPA queries
     * again (the {@code @Where} filter no longer excludes it). Use with care — typically
     * only ADMIN-level operations should trigger a restore.
     *
     * @param entity     the entity to restore
     * @param repository the repository used to persist the change
     * @param <T>        any entity type extending {@link BaseEntity}
     */
    @Transactional   //Participates in the caller's transaction if one exists; otherwise starts a new transaction
    public <T extends BaseEntity> void restore(T entity, JpaRepository<T, UUID> repository) {  // work for all entities , no duplication
        if (entity.getDeletedAt() == null) {
            log.warn("restore called on a non-deleted entity: id={}, type={}",
                    entity.getId(), entity.getClass().getSimpleName());
            return; // idempotent — no-op if already active
        }

        entity.setDeletedAt(null);
        repository.save(entity);

        log.info("Restored entity: id={}, type={}", entity.getId(),
                entity.getClass().getSimpleName());
    }
}
