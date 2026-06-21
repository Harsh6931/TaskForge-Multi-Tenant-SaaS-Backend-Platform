# AGENTS.md — TaskForge: Multi-Tenant SaaS Backend

This file is the standing instruction set for AI coding agents (Antigravity, Codex) working in this repo. Place this file at the project root as `AGENTS.md` — both tools read it automatically before starting work.

**Legend:**
🧠 = Do this yourself / decide this yourself before instructing the agent. Do NOT delegate this step. These are the decisions and write-ups that carry the actual learning + interview value. Everything without 🧠 can be delegated to the agent directly.

---

## 0. Global Rules for Agents (put this at the top of every session)

```
Tech stack: Java 21 + Spring Boot 3 (backend), PostgreSQL 16 (database),
React 18 + TypeScript + Vite (frontend), Python 3.11 + FastAPI (ai-service),
Redis (cache/rate-limit/pubsub), Docker + docker-compose (local dev).

Rules:
- Every tenant-scoped table MUST include a tenant_id column.
- Never write raw string-concatenated SQL — use JPA/parameterized queries only.
- All entities use soft-delete (deleted_at), never hard DELETE, except audit_logs (append-only, never delete).
- Follow the schema in SCHEMA.md exactly — do not invent new columns or rename existing ones without flagging me first.
- Follow the API contract in API_CONTRACT.md exactly — do not change response shapes without flagging me first.
- Write a unit test for every service method that contains business logic.
- After generating code, summarize what you changed and why, in plain language, before I review.
```

🧠 Read the above block, understand every line, then paste it into your agent's persistent rules/workspace config before starting Phase 0.

---

## Phase 0 — Setup & Planning (Done)

1. 🧠 Decide multi-tenancy strategy and write a 3–5 sentence justification in `ARCHITECTURE.md` (shared schema + RLS vs. schema-per-tenant vs. db-per-tenant). Do this BEFORE prompting any agent — this is the single most-asked interview question about this project.
2. 🧠 Sketch the ERD by hand or in any tool (Excalidraw, draw.io) — even rough. Save as `erd.png` in `/docs`.
3. Instruct agent: "Initialize a monorepo with folders `/backend`, `/ai-service`, `/frontend`, `/infra`, `/docs`. Add a root `.gitignore` for Java, Python, and Node."
4. Instruct agent: "Scaffold `/backend` as a Spring Boot 3 Maven project, Java 21, with dependencies: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-security, spring-boot-starter-validation, postgresql, lombok, jjwt (for JWT)."
5. Instruct agent: "Scaffold `/ai-service` as a FastAPI project with `pyproject.toml`, dependencies: fastapi, uvicorn, asyncpg, pgvector, anthropic (or openai), pydantic."
6. Instruct agent: "Scaffold `/frontend` as a Vite + React + TypeScript project with TailwindCSS and react-router-dom installed."
7. Instruct agent: "Create `/infra/docker-compose.yml` with services: postgres (with pgvector extension enabled), redis, backend, ai-service, frontend."

**Deliverable:** Repo boots locally with `docker-compose up`, even with empty services.

---

## Phase 1 — Tenant Isolation & Core Schema

🧠 Before instructing the agent, read `SCHEMA.md` (below) fully and make sure you understand WHY each table has a `tenant_id`, and how RLS will check it. If you don't understand a table, ask me to explain it before proceeding — don't let the agent build something you can't defend in an interview.

1. Instruct agent: "Implement the exact schema in SCHEMA.md as Flyway/Liquibase migration files in `/backend/src/main/resources/db/migration`. Use the table and column definitions exactly as written — do not add or remove columns."
2. Instruct agent: "Enable PostgreSQL Row-Level Security on every tenant-scoped table. Create a policy that filters rows where `tenant_id = current_setting('app.current_tenant_id')::uuid`."
3. Instruct agent: "Create a Spring `OncePerRequestFilter` that, after authenticating the request, runs `SET app.current_tenant_id = '<tenant_id>'` on the connection for that request's transaction."
4. 🧠 Write a test scenario yourself (in plain English first): "Tenant A creates a project. Tenant B queries `/projects` with a deliberately broken/missing WHERE clause. Confirm zero rows from Tenant A are returned." Then instruct the agent to implement this as an automated integration test.

**Deliverable:** Migrations run clean, RLS isolation test passes.

---

## Phase 2 — Authentication & Authorization

1. Instruct agent: "Implement JWT auth with access token (15 min expiry) and refresh token (7 day expiry, stored hashed in `refresh_tokens` table). Endpoints: signup, login, refresh, logout."
2. Instruct agent: "Implement tenant-aware login: after auth, return the list of tenants the user belongs to (from `tenant_users`). Add a `/auth/switch-tenant/{tenant_id}` endpoint that issues a new token scoped to that tenant."
3. 🧠 Decide your RBAC permission matrix yourself first — write it as a simple table: which of ADMIN/MANAGER/MEMBER/VIEWER can do what (create project, delete task, invite user, change billing, view audit log). Put this in `docs/RBAC.md`.
4. Instruct agent: "Implement `@PreAuthorize`-based role checks on every endpoint exactly matching the matrix in docs/RBAC.md."
5. Instruct agent: "Implement API key generation per tenant: generate a random key, store only its SHA-256 hash, allow auth via `Authorization: ApiKey <key>` header for the `/api/v1/*` routes."

**Deliverable:** Login, tenant-switch, role-restricted endpoints, and API key auth all work and are covered by tests.

---

## Phase 3 — Core Domain: Projects & Tasks

Follow exact API contract in `API_CONTRACT.md`. Don't let the agent improvise field names.

1. Instruct agent: "Implement full CRUD for Project, Task, Comment, Label entities per SCHEMA.md and API_CONTRACT.md."
2. Instruct agent: "Implement task status transitions: BACKLOG → IN_PROGRESS → DONE only (reject invalid transitions, e.g., DONE → BACKLOG should require explicit reopen action, not a plain PATCH)."
3. Instruct agent: "Implement optimistic locking on Task using the `version` column — return 409 Conflict if a PATCH request's version doesn't match the current row."
4. Instruct agent: "Implement cursor-based pagination on `GET /projects/{id}/tasks` — accept `?cursor=&limit=`, return `next_cursor` in response."
5. Instruct agent: "Add Postgres `tsvector` full-text search on Task title+description, exposed via `GET /tasks/search?q=`."

**Deliverable:** Postman/Bruno collection demonstrating full task lifecycle, conflict handling, and search.

---

## Phase 4 — Billing & Subscriptions

1. 🧠 Decide your plan limits yourself (seats/projects/storage/API calls per tier) and write them into `SCHEMA.md`'s `plans` seed data — don't let the agent invent arbitrary numbers.
2. Instruct agent: "Implement `Plan`, `Subscription`, `UsageRecord` entities per SCHEMA.md. Seed three plans: FREE, PRO, ENTERPRISE with the limits I specified."
3. Instruct agent: "Implement a `UsageGuard` service that checks current usage against the tenant's plan limit before allowing project/task creation or API calls — return 402 Payment Required if exceeded."
4. Instruct agent: "Integrate Stripe (test mode) for checkout session creation and webhook handling for `customer.subscription.created/updated/deleted` and `invoice.payment_failed`. Make webhook handling idempotent using the Stripe event ID."

**Deliverable:** A tenant can hit a plan limit and get blocked; upgrading via Stripe test checkout lifts the limit.

---

## Phase 5 — Audit Logging

1. Instruct agent: "Implement an `AuditLog` entity (append-only, no update/delete) per SCHEMA.md. Create an `AuditService.log(tenantId, userId, action, resourceType, resourceId, metadata)` method."
2. Instruct agent: "Call `AuditService.log(...)` from every state-changing endpoint: role changes, deletions, billing changes, API key creation/revocation."
3. Instruct agent: "Implement `GET /audit-logs?action=&userId=&from=&to=` (Admin role only) with pagination."
4. 🧠 Decide and document yourself: what counts as a "critical" action worth auditing vs. noise (e.g., do you log every task view? Probably not — decide and justify in `docs/AUDIT_POLICY.md`).

**Deliverable:** Every critical action produces a queryable, immutable audit record.

---

## Phase 6 — AI Layer (in `/ai-service`)

🧠 Pick exactly 3–4 of the following yourself — don't ask the agent to build all of them, you need to be able to explain each one deeply in an interview:

- [ ] Semantic search (embeddings + pgvector similarity search over tasks)
- [ ] AI weekly digest summarizer
- [ ] Smart auto-tagging/labeling on task creation
- [ ] RAG support assistant over workspace data
- [ ] Usage anomaly detection

🧠 Once you've picked, write one sentence each on WHY you picked them (ties to your target role — e.g., AI-adjacent roles → pick semantic search + RAG assistant).

1. Instruct agent: "In `/ai-service`, implement `POST /embed` that takes a task's title+description, generates an embedding via the Anthropic/OpenAI API, and stores it in the `task_embeddings` table (pgvector column)."
2. Instruct agent: "Implement `GET /search?q=&tenant_id=` that embeds the query and returns top-5 similar tasks via cosine similarity, filtered by `tenant_id`."
3. Instruct agent: "Implement [your chosen second feature] as a new FastAPI route, following the same tenant-scoping pattern."
4. Instruct agent: "In `/backend`, add a service client that calls `/ai-service` endpoints, authenticated via a shared internal service token (not user JWT)."

**Deliverable:** At least one AI feature works end-to-end from the React frontend.

---

## Phase 7 — Real-Time Collaboration

1. Instruct agent: "Implement WebSocket (STOMP over SockJS) in Spring Boot for live task updates. Broadcast to `/topic/tenant/{tenantId}/project/{projectId}` on task create/update/delete."
2. Instruct agent: "Use Redis pub/sub to fan out WebSocket events across multiple backend instances (so it works even if horizontally scaled)."
3. Instruct agent: "Implement a simple in-app notification system: `notifications` table, `GET /notifications`, mark-as-read endpoint."

**Deliverable:** Two browser tabs, different users, live board updates.

---

## Phase 8 — Observability, Caching, Performance

1. Instruct agent: "Add Redis caching for `GET /dashboard/stats` with a 60-second TTL, invalidated on relevant writes."
2. 🧠 Decide rate limit thresholds per plan tier yourself (e.g., FREE: 60 req/min, PRO: 300 req/min) — document in `docs/RATE_LIMITS.md`.
3. Instruct agent: "Implement a Redis token-bucket rate limiter per tenant per the thresholds in docs/RATE_LIMITS.md, returning 429 with `Retry-After` header when exceeded."
4. Instruct agent: "Add structured JSON logging with a request-trace-id (UUID) propagated through every log line per request."
5. 🧠 Run `EXPLAIN ANALYZE` yourself on your 2-3 slowest queries (use the dashboard stats query and task search query as candidates). Read the output yourself. Then instruct the agent to add the specific index you decide is needed — don't let the agent guess at indexes blindly.

**Deliverable:** Rate limiting demonstrably works; you have a documented before/after query optimization.

---

## Phase 9 — Deployment

1. Instruct agent: "Write production Dockerfiles for `/backend`, `/ai-service`, `/frontend` (multi-stage builds, minimal final image size)."
2. Instruct agent: "Write a GitHub Actions workflow: on push to main, run backend tests, run frontend build, then deploy to Railway/Render (use placeholder deploy step, I'll fill in secrets)."
3. 🧠 Create the actual Railway/Render/Fly.io account and connect it yourself — don't share credentials with the agent.

**Deliverable:** Live public demo URL.

---

## Phase 10 — Documentation & Resume Packaging

1. 🧠 Write the README yourself (agent can draft, but you must edit until you could explain every sentence unprompted): problem statement, architecture diagram, tech stack, key decisions, screenshots/GIF.
2. 🧠 Write `docs/ENGINEERING_DECISIONS.md` yourself — the "why RLS," "why cursor pagination," "why idempotent webhooks" trade-off explanations. This is your interview script. The agent can help you draft, but the reasoning must be yours.
3. 🧠 Record a 2–3 minute demo video walking through tenant isolation proof + your chosen AI feature.
4. 🧠 Write and rehearse your 60-second "tell me about this project" pitch out loud, at least 3 times, before your first interview that references it.

---

## SCHEMA.md (reference — exact table list)

```
tenants(id uuid pk, name, slug unique, plan_id fk, created_at, updated_at, deleted_at)
users(id uuid pk, email unique, password_hash, full_name, created_at, updated_at, deleted_at)
tenant_users(id uuid pk, tenant_id fk, user_id fk, role enum[ADMIN,MANAGER,MEMBER,VIEWER], joined_at)
projects(id uuid pk, tenant_id fk, name, description, created_by fk users, created_at, updated_at, deleted_at)
tasks(id uuid pk, tenant_id fk, project_id fk, title, description, status enum[BACKLOG,IN_PROGRESS,DONE],
      priority enum[LOW,MEDIUM,HIGH], assignee_id fk users nullable, due_date, version int default 0,
      created_at, updated_at, deleted_at)
comments(id uuid pk, tenant_id fk, task_id fk, user_id fk, body, created_at, updated_at, deleted_at)
labels(id uuid pk, tenant_id fk, name, color)
task_labels(task_id fk, label_id fk, primary key(task_id, label_id))
plans(id uuid pk, name, max_seats int, max_projects int, max_storage_mb int, max_api_calls_month int, price_cents int)
subscriptions(id uuid pk, tenant_id fk, plan_id fk, status enum[ACTIVE,CANCELLED,PAST_DUE],
              stripe_subscription_id, current_period_start, current_period_end)
usage_records(id uuid pk, tenant_id fk, metric enum[API_CALLS,STORAGE_MB], value int, period_start, period_end)
audit_logs(id uuid pk, tenant_id fk, user_id fk, action, resource_type, resource_id, metadata jsonb, created_at)
api_keys(id uuid pk, tenant_id fk, key_hash, name, created_at, last_used_at, revoked_at)
task_embeddings(task_id uuid pk fk, tenant_id fk, embedding vector(1536), updated_at)
notifications(id uuid pk, tenant_id fk, user_id fk, type, payload jsonb, read_at, created_at)
refresh_tokens(id uuid pk, user_id fk, token_hash, expires_at, created_at, revoked_at)
```

---

## API_CONTRACT.md (reference — core endpoints)

```
POST   /auth/signup                  { email, password, full_name }
POST   /auth/login                   { email, password } -> { access_token, refresh_token, tenants[] }
POST   /auth/refresh                 { refresh_token } -> { access_token }
POST   /auth/switch-tenant/{tenantId} -> { access_token }

GET    /projects                     -> [ { id, name, description, created_at } ]
POST   /projects                     { name, description }
GET    /projects/{id}/tasks?cursor=&limit=&status=&assignee=
POST   /projects/{id}/tasks          { title, description, priority, assignee_id, due_date }
PATCH  /tasks/{id}                   { ...fields, version }   -> 409 if version mismatch
POST   /tasks/{id}/comments          { body }
GET    /tasks/search?q=

GET    /billing/plans
POST   /billing/checkout-session     { plan_id } -> { checkout_url }
POST   /billing/webhook              (Stripe signature verified)

GET    /audit-logs?action=&userId=&from=&to=
POST   /api-keys                     { name } -> { key }  (shown once)
DELETE /api-keys/{id}

GET    /notifications
PATCH  /notifications/{id}/read

WS     /ws/tenant/{tenantId}/project/{projectId}
```

---

## Quick Checklist of 🧠 Items (don't skip these)

- [ ] 🧠 Multi-tenancy strategy justification written
- [ ] 🧠 ERD sketched
- [ ] 🧠 RLS isolation test scenario written before delegating
- [ ] 🧠 RBAC permission matrix designed
- [ ] 🧠 Plan limits decided
- [ ] 🧠 AI features picked (3–4) + reasoning written
- [ ] 🧠 Rate limit thresholds decided
- [ ] 🧠 EXPLAIN ANALYZE run personally, index decision made by you
- [ ] 🧠 Deployment account created/connected personally
- [ ] 🧠 README written/edited until fully understood
- [ ] 🧠 Engineering decisions doc written
- [ ] 🧠 Demo video recorded
- [ ] 🧠 60-second pitch rehearsed aloud
```
