# Phase 1 — Tenant Isolation & Core Infrastructure
### Implementation Breakdown (Small Learning Goals)

> **Current state:** Phase 0 and Phase 1 Goals 1–5 complete. Flyway migrations V1–V7 run clean. RLS enabled. Tenant context stack wired. BaseEntity, Tenant, User, TenantUser, TenantUserRole, Project entities created with @Where soft-delete. JpaConfig activates auditing.
>
> **Phase 1 Goal:** Rock-solid multi-tenant foundation — every table has `tenant_id`, RLS enforces isolation at the DB layer, and a Spring filter injects the tenant context per request.

---

## 🗂️ Overview Map

```
Goal 1 → Understand Flyway (migrations as code)
Goal 2 → Write the core schema (all tables, exact SCHEMA.md)
Goal 3 → Enable RLS — the "magic safety net"
Goal 4 → Build the TenantContext + Spring Filter (connects JWT → DB session variable)
Goal 5 → Write JPA Entities (Java mirrors of the tables)
Goal 6 → Wire it all: BaseEntity + soft-delete pattern
Goal 7 → Write the RLS isolation integration test (the proof)
```

---

## Goal 1 — Understand Flyway (Database Migrations as Code)

> **Why first?** Everything in Phase 1 lives in migration files. If you don't understand Flyway, you'll be confused by your own code.

### What to learn
- What is a database migration tool and why is it better than running SQL manually?
- Flyway naming convention: `V{version}__{description}.sql` (e.g., `V1__create_tenants.sql`)
- How Flyway tracks applied migrations (`flyway_schema_history` table)
- What happens if you edit an already-applied migration (❌ never do this — Flyway checksums)

### What to build
- [x] Confirm `spring.flyway.enabled=true` and the migration path is `classpath:db/migration`
- [x] Add validation settings so changed or out-of-order migrations fail fast
- [x] Create a throwaway `V1__smoke_test.sql` migration that creates and seeds `flyway_smoke_test`
- [x] Run the stack and verify `flyway_smoke_test` and `flyway_schema_history` in PostgreSQL

### Files touched
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V1__smoke_test.sql` (replace after testing and resetting the local database)

### Key concept to internalize
> *"Flyway is git for your database. Once a migration is committed and run, it's permanent. You add new migrations, never edit old ones."*

---

## Goal 2 — Write the Core Schema (Flyway Migration Files)

> **Why split from Goal 1?** Schema design is a separate thinking task. You want to deeply understand each table before writing code.

### Study the SCHEMA.md first (🧠 your job)
Before writing SQL, re-read each table and answer these questions yourself:
1. Why does `tenant_users` exist instead of just a `tenant_id` column on `users`? *(Answer: a user can belong to multiple tenants — Slack workspace model)*
2. Why does `tasks` have a `version` column? *(Answer: optimistic locking — prevents lost updates)*
3. Why `deleted_at` instead of `DELETE`? *(Answer: soft-delete — data recovery, audit trail, RLS-safe)*

### Migration file plan (split by logical group)

| File | Tables | Why split this way |
|---|---|---|
| `V1__create_tenants_and_users.sql` | `tenants`, `users`, `tenant_users` | The identity/auth core |
| `V2__create_projects_and_tasks.sql` | `projects`, `tasks`, `comments`, `labels`, `task_labels` | The product domain |
| `V3__create_billing.sql` | `plans`, `subscriptions`, `usage_records` | Billing — separate concern |
| `V4__create_auth_and_keys.sql` | `refresh_tokens`, `api_keys` | Auth infrastructure |
| `V5__create_audit_and_notifications.sql` | `audit_logs`, `notifications` | Observability |
| `V6__create_ai_tables.sql` | `task_embeddings` | AI layer (needs pgvector) |

### What to build
- [x] `V1__create_tenants_and_users.sql` — tenants, users (with `password_hash`), tenant_users with role ENUM
- [x] `V2__create_projects_and_tasks.sql` — projects, tasks (with `status` ENUM, `priority` ENUM, `version INT DEFAULT 0`), comments, labels, task_labels
- [x] `V3__create_billing.sql` — plans, subscriptions (with `status` ENUM), usage_records (with `metric` ENUM)
- [x] `V4__create_auth_and_keys.sql` — refresh_tokens, api_keys
- [x] `V5__create_audit_and_notifications.sql` — audit_logs (`metadata JSONB`), notifications (`payload JSONB`)
- [x] `V6__create_ai_tables.sql` — enable pgvector extension, create task_embeddings with `embedding vector(1536)`

### Key SQL patterns to use in every table

```sql
-- Every table: UUID primary key
id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

-- Every tenant-scoped table: tenant_id FK
tenant_id UUID NOT NULL REFERENCES tenants(id),

-- Every table except audit_logs: soft delete
deleted_at TIMESTAMPTZ,

-- Timestamps
created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
```

### Composite indexes (critical for multi-tenant performance)

```sql
-- Every tenant-scoped table needs this index pattern
CREATE INDEX idx_projects_tenant_id ON projects(tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_tenant_project ON tasks(tenant_id, project_id) WHERE deleted_at IS NULL;
```

### Files to create
- `db/migration/V1__create_tenants_and_users.sql`
- `db/migration/V2__create_projects_and_tasks.sql`
- `db/migration/V3__create_billing.sql`
- `db/migration/V4__create_auth_and_keys.sql`
- `db/migration/V5__create_audit_and_notifications.sql`
- `db/migration/V6__create_ai_tables.sql`

---

## Goal 3 — Enable PostgreSQL Row-Level Security (RLS)

> **This is the core of Phase 1.** RLS is what separates "I added a WHERE clause" from "the database enforces isolation."

### What to learn first
- What is RLS? A PostgreSQL feature where the DB itself filters rows based on a policy — even if the app forgets a WHERE clause.
- How session variables work: `SET app.current_tenant_id = 'uuid'` — sets a variable for the current connection/transaction.
- `current_setting('app.current_tenant_id')::uuid` — reads that variable inside a policy.
- `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` — turns RLS on.
- `CREATE POLICY` — defines the filter rule.

### Migration file: `V7__enable_rls.sql`

```sql
-- Pattern applied to EVERY tenant-scoped table:
ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects FORCE ROW LEVEL SECURITY;  -- applies even to table owner

CREATE POLICY tenant_isolation_policy ON projects
  USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
```

> `FORCE ROW LEVEL SECURITY` — applies RLS even to the superuser/table owner. Without this, the app DB user (if it's the owner) would bypass RLS.

### What to build
- [x] `V7__enable_rls.sql` — enable RLS + create `tenant_isolation_policy` on: `projects`, `tasks`, `comments`, `labels`, `task_labels`, `subscriptions`, `usage_records`, `audit_logs`, `api_keys`, `notifications`, `task_embeddings`, `tenant_users`
- [x] Note: `tenants`, `users`, `plans` are NOT tenant-scoped (they're global) — no RLS needed
- [x] Note: `refresh_tokens` is user-scoped, not tenant-scoped — skip RLS or apply user-based policy

### Files to create
- `db/migration/V7__enable_rls.sql`

### Key concept to internalize
> *"Without FORCE ROW LEVEL SECURITY, a superuser DB connection bypasses all policies. In production, your app should connect as a restricted role, not the owner. But FORCE ensures correctness regardless."*

---

## Goal 4 — TenantContext + Spring Filter (The Glue Layer)

> **Why this matters:** RLS reads `app.current_tenant_id` from the DB session. Someone has to SET it. That's this filter's job — it bridges JWT → DB.

### Component breakdown

```
HTTP Request
    │
    ▼
[JwtAuthenticationFilter]          ← Phase 2 (full JWT) — placeholder for now
    │  extracts tenant_id from token
    ▼
[TenantContextHolder]              ← Thread-local storage for tenant_id
    │
    ▼
[TenantFilter (OncePerRequestFilter)] ← Runs SET app.current_tenant_id on the DB connection
    │
    ▼
[Repository / JPA]                 ← RLS policy reads the session variable, filters rows
```

### What to build

#### 4a — TenantContextHolder
- [x] Create `com.taskforge.tenant.TenantContextHolder` — uses `InheritableThreadLocal<UUID>` (inherits to @Async children)
- [x] Methods: `setTenantId(UUID)`, `getTenantId()`, `clear()`
- [x] Learn: Why ThreadLocal? Because each HTTP request runs on its own thread. The tenant ID must not bleed between requests.

#### 4b — TenantFilter
- [x] Create `com.taskforge.tenant.TenantFilter extends OncePerRequestFilter`
- [x] In `doFilterInternal`:
  1. Read tenant ID from request header `X-Tenant-ID` (placeholder — Phase 2 will read from JWT)
  2. Call `TenantContextHolder.setTenantId(uuid)`
  3. Continue filter chain
  4. In `finally` block: `TenantContextHolder.clear()` ← **critical**, prevents thread-pool leakage

#### 4c — TenantAwareDataSource (the actual SQL injection)
- [x] Create `com.taskforge.tenant.TenantConnectionInterceptor` — uses JdbcTemplate to run `SET LOCAL app.current_tenant_id = ?`
- [x] Uses Option A: explicit call at transaction start via `applyTenantContext()`
  - **Option A (simpler):** `JdbcTemplate.update("SET LOCAL app.current_tenant_id = ?", tenantId)` at start of transaction
  - **Option B (production-grade):** Extend `AbstractRoutingDataSource` or use a Hibernate `ConnectionProvider` (Phase 8 upgrade)

> For now, use a `@Aspect` around `@Transactional` methods, or a `DataSourceConnectionInterceptor`. The exact pattern can be refined — the key is that BEFORE any SQL runs, the session variable is set.

### Files to create
- `com/taskforge/tenant/TenantContextHolder.java`
- `com/taskforge/tenant/TenantFilter.java`
- `com/taskforge/config/TenantConfig.java` (registers the filter as a Spring bean)

---

## Goal 5 — JPA Entities (Java Mirror of the Schema)

> **Why now?** Entities let you interact with the DB from Java code. You need them for the integration test in Goal 7.

### What to learn
- `@Entity`, `@Table`, `@Column` — basic JPA annotations
- `@Id`, `@GeneratedValue` with UUID strategy
- `@Enumerated(EnumType.STRING)` — maps Java enums to Postgres ENUMs
- `@ManyToOne`, `@OneToMany` — relationships
- Lombok: `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` — eliminates boilerplate

### Entity plan (only what Phase 1 needs — don't build everything yet)

| Entity class | Table | Notes |
|---|---|---|
| `Tenant` | `tenants` | Has `planId` FK (nullable for now) |
| `User` | `users` | Has `passwordHash` (not `password`) |
| `TenantUser` | `tenant_users` | Has `role` enum: ADMIN/MANAGER/MEMBER/VIEWER |
| `Project` | `projects` | Has `tenantId` — used in the isolation test |

> Save Task, Comment, Label etc. for Phase 3. Keep Phase 1 entities minimal.

### BaseEntity pattern (DRY — avoids repeating timestamps everywhere)

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Column
    private Instant deletedAt;  // soft delete
}
```

### Files to create
- `com/taskforge/common/BaseEntity.java`
- `com/taskforge/tenant/entity/Tenant.java`
- `com/taskforge/user/entity/User.java`
- `com/taskforge/user/entity/TenantUser.java` + `TenantUserRole.java` (enum)
- `com/taskforge/project/entity/Project.java`

---

## Goal 6 — Repositories + Soft-Delete Pattern

> **Why a separate goal?** The soft-delete pattern subtly changes every query. Understanding it now prevents bugs in all future phases.

### What to learn
- `JpaRepository<T, ID>` — gives you `findById`, `save`, `findAll` etc.
- Why soft-delete breaks `findAll()`: it will return deleted records unless you filter
- Two solutions:
  - `@Where(clause = "deleted_at IS NULL")` — Hibernate annotation, auto-applies to all queries
  - Custom `@Query` on every method — verbose but explicit

### What to build
- [ ] Add `@Where(clause = "deleted_at IS NULL")` to every entity that has `deleted_at`
- [ ] Create `TenantRepository extends JpaRepository<Tenant, UUID>`
- [ ] Create `UserRepository extends JpaRepository<User, UUID>`
- [ ] Create `ProjectRepository extends JpaRepository<Project, UUID>`
- [ ] Create a `SoftDeleteService` helper with a `softDelete(BaseEntity entity)` method that sets `deletedAt = Instant.now()` and saves

### Files to create
- `com/taskforge/tenant/repository/TenantRepository.java`
- `com/taskforge/user/repository/UserRepository.java`
- `com/taskforge/project/repository/ProjectRepository.java`
- `com/taskforge/common/SoftDeleteService.java` (optional helper)

---

## Goal 7 — RLS Isolation Integration Test (The Proof)

> **This is the Phase 1 deliverable.** If this test passes, you've proven that RLS works. This is also something you'll describe in interviews.

### Test scenario (write this in plain English first — 🧠 your job)
> *"Tenant A creates a project. We then switch the DB session to Tenant B's context. We query ALL projects with no WHERE clause. The result must be empty — Tenant A's project must NOT appear."*

### What to build
- [ ] Create a Spring Boot integration test: `TenantIsolationIntegrationTest`
- [ ] Use `@SpringBootTest` + `@Transactional`
- [ ] Test steps:
  1. Insert Tenant A and Tenant B into DB (use `JdbcTemplate` directly to bypass RLS for setup)
  2. Set session variable to Tenant A: `jdbcTemplate.execute("SET LOCAL app.current_tenant_id = '" + tenantAId + "'")`
  3. Insert a Project for Tenant A via `ProjectRepository.save(...)`
  4. Switch session variable to Tenant B: `SET LOCAL app.current_tenant_id = tenantBId`
  5. Call `projectRepository.findAll()` — assert result is **empty**
  6. Switch back to Tenant A — assert result has **1 project**

### Files to create
- `backend/src/test/java/com/taskforge/TenantIsolationIntegrationTest.java`

### Key learning
> *"This test is your interview story. 'I wrote an integration test that proved RLS works by querying across tenant boundaries with no application-level filter — zero rows returned.' That's a concrete, specific, impressive answer."*

---

## Phase 1 Completion Checklist

| Goal | Description | Status |
|---|---|---|
| Goal 1 | Flyway understood + smoke test migration | ✅ |
| Goal 2 | All 6 schema migrations written + verified in psql | ✅ |
| Goal 3 | RLS enabled on all tenant-scoped tables | ✅ |
| Goal 4 | TenantContextHolder + TenantFilter wired up | ✅ |
| Goal 5 | JPA entities for Tenant, User, TenantUser, Project | ✅ |
| Goal 6 | Repositories + soft-delete pattern | ⬜ |
| Goal 7 | RLS isolation integration test passes ✅ | ⬜ |

---

## Package Structure After Phase 1

```
com.taskforge
├── TaskForgeApplication.java
├── common/
│   └── BaseEntity.java
├── config/
│   └── TenantConfig.java           ← registers TenantFilter
├── tenant/
│   ├── TenantContextHolder.java    ← ThreadLocal storage
│   ├── TenantFilter.java           ← OncePerRequestFilter
│   └── repository/
│       └── TenantRepository.java
├── user/
│   ├── entity/
│   │   ├── User.java
│   │   ├── TenantUser.java
│   │   └── TenantUserRole.java     ← ENUM: ADMIN, MANAGER, MEMBER, VIEWER
│   └── repository/
│       └── UserRepository.java
└── project/
    ├── entity/
    │   └── Project.java
    └── repository/
        └── ProjectRepository.java
```

---

## Key Concepts Mastered After Phase 1

| Concept | Where you used it |
|---|---|
| Flyway migrations | V1–V7 SQL files |
| PostgreSQL RLS | `V7__enable_rls.sql` + RLS policies |
| Session variables | `SET app.current_tenant_id` |
| `FORCE ROW LEVEL SECURITY` | Why the table owner isn't exempt |
| ThreadLocal | `TenantContextHolder` |
| `OncePerRequestFilter` | `TenantFilter` |
| Soft-delete with `@Where` | All entities |
| `@MappedSuperclass` | `BaseEntity` |
| Integration testing with Spring Boot | `TenantIsolationIntegrationTest` |
