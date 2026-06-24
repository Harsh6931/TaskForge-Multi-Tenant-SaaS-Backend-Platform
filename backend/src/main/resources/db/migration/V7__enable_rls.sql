-- Enable PostgreSQL Row-Level Security on every tenant-scoped table.

-- How it works:
--   1. ENABLE ROW LEVEL SECURITY  → activates RLS for the table.
--   2. FORCE ROW LEVEL SECURITY   → applies the policy even to the table
--      owner / superuser role. Without FORCE, the DB owner bypasses all
--      policies — dangerous in production.
--   3. CREATE POLICY               → defines the predicate:
--        tenant_id = current_setting('app.current_tenant_id', true)::uuid
--      The Spring TenantFilter sets this session variable at the start of
--      every request, so the DB enforces isolation automatically, even if
--      the application layer forgets a WHERE clause.

--     I have applied RLS to all table using tenant_id ( for data isolation)

-- Tables NOT covered (no tenant_id column — they are global):
--   tenants, users, plans, refresh_tokens

ALTER TABLE tenant_users ENABLE ROW LEVEL SECURITY;          --Activate RLS
ALTER TABLE tenant_users FORCE ROW LEVEL SECURITY;           -- Enforce RLS defined policy  strictly

CREATE POLICY tenant_isolation_policy ON tenant_users         -- create RLS policy
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── projects ────────────────────────────────────────────────
ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON projects
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── tasks ───────────────────────────────────────────────────
ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE tasks FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON tasks
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── comments ────────────────────────────────────────────────
ALTER TABLE comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE comments FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON comments
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── labels ──────────────────────────────────────────────────
ALTER TABLE labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE labels FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON labels
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── task_labels (join table — tenant_id via task FK) ─────────
-- task_labels has no direct tenant_id column, so we join through tasks.
-- Policy: allow if the referenced task belongs to the current tenant.
ALTER TABLE task_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_labels FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON task_labels
    USING (
        EXISTS (                    -- if atleast one row satisfy condition  -- eg at Select 2 if even 1 row then for 2 it exists
            SELECT 1 FROM tasks t
            WHERE t.id = task_labels.task_id
              AND t.tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID
        )
    );

-- ── subscriptions ───────────────────────────────────────────
ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON subscriptions
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── usage_records ───────────────────────────────────────────
ALTER TABLE usage_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_records FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON usage_records
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── audit_logs ──────────────────────────────────────────────
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON audit_logs
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── api_keys ────────────────────────────────────────────────
ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON api_keys
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── notifications ───────────────────────────────────────────
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON notifications
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

-- ── task_embeddings ─────────────────────────────────────────
ALTER TABLE task_embeddings ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_embeddings FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON task_embeddings
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
