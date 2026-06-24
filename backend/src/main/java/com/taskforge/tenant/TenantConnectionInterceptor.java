package com.taskforge.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * TenantConnectionInterceptor — Injects the current tenant ID into the PostgreSQL
 * session variable {@code app.current_tenant_id} before any SQL executes.
 *
 * <p><b>Why this is needed:</b>
 * RLS policies read {@code current_setting('app.current_tenant_id', true)::uuid} to filter
 * rows. That session variable must be set on the JDBC connection <em>before</em> any SELECT,
 * INSERT, UPDATE, or DELETE runs — otherwise PostgreSQL has no tenant context and either
 * returns nothing (when the setting is missing and the policy evaluates to false) or
 * returns data incorrectly.
 *
 * <p><b>How it works (Option A — explicit call at transaction start):</b>
 * Call {@link #applyTenantContext()} at the start of each transactional service method.
 * This runs {@code SET LOCAL app.current_tenant_id = '<uuid>'} on the current connection.
 * {@code SET LOCAL} scopes the variable to the current transaction — it is automatically
 * cleared when the transaction commits or rolls back, so there is no risk of cross-request
 * leakage at the DB layer.
 *
 * <p><b>Usage:</b>
 * Inject this bean into your service classes and call {@code applyTenantContext()} at the
 * beginning of any method annotated with {@code @Transactional}. Example:
 * <pre>{@code
 *   @Transactional
 *   public List<Project> getAllProjects() {
 *       tenantConnectionInterceptor.applyTenantContext();
 *       return projectRepository.findAll();
 *   }
 * }</pre>
 *
 * <p><b>Phase 2 upgrade path:</b>
 * In Phase 8 we will upgrade this to a Hibernate {@code StatementInspector} or a
 * DataSource proxy (e.g., p6spy / datasource-proxy) so the SET runs automatically on
 * every connection borrow — removing the need for manual calls in service methods.
 */
@Slf4j
@Component
public class TenantConnectionInterceptor {

    private static final String SET_TENANT_SQL = "SET LOCAL app.current_tenant_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public TenantConnectionInterceptor(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Injects the current tenant ID into the PostgreSQL session for the active transaction.
     *
     * <p>Must be called within an active Spring transaction (i.e., inside a method
     * annotated with {@code @Transactional}) so that the JDBC connection is already bound
     * to the transaction and {@code SET LOCAL} scopes correctly.
     *
     * <p>If no tenant ID is present in {@link TenantContextHolder} (e.g., for public
     * endpoints that don't require tenant scoping), this method is a no-op.
     */
    public void applyTenantContext() {
        UUID tenantId = TenantContextHolder.getTenantId();

        if (tenantId == null) {
            log.debug("No tenant context to apply — skipping SET LOCAL");
            return;
        }

        log.debug("Applying tenant context to DB session: tenantId={}", tenantId);
        jdbcTemplate.update(SET_TENANT_SQL, tenantId.toString());

        // Register a synchronization so we can log when the transaction ends.
        // This is useful for debugging and confirms the SET LOCAL was scoped correctly.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        log.debug("Transaction completed (status={}) — app.current_tenant_id " +
                                "auto-cleared by PostgreSQL SET LOCAL scope", status);
                    }
                }
            );
        }
    }
}
