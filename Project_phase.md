# Multi-Tenant SaaS Backend — Project Roadmap

**Project Name (working title):** TaskForge — Multi-Tenant Project & Task Management Platform
**Goal:** Build a production-grade, AI-augmented multi-tenant SaaS backend that demonstrates database mastery, system design, security, and modern AI integration — strong enough to anchor a FAANG/fintech resume.

---


## Why This Domain

A project/task management tool (Linear/Jira-style) is chosen because it naturally requires:
- Multiple tenants (companies) with isolated data
- Role-based permissions
- Billing tiers based on usage (seats, storage, API calls)
- Real-time updates (task changes, comments)
- Rich data for AI features (descriptions, comments, activity logs)

---

## Tech Stack

| Layer | Choice | Reason |
|---|---|---|
| Backend | Java + Spring Boot | Matches your roadmap |
| Database | PostgreSQL | RLS, JSONB, pgvector |
| Frontend | React + TypeScript | Matches your roadmap |
| AI Layer | Python (FastAPI) + Anthropic/OpenAI API | Microservice, fits your AI/ML 10% track |
| Cache/Queue | Redis | Caching, rate limiting, pub/sub |
| Auth | JWT + Spring Security | Stateless, scalable |
| Real-time | WebSockets (STOMP) | Live task/comment updates |
| Deployment | Docker + GitHub Actions (CI/CD) | Industry standard |
| Observability | Prometheus + Grafana (or simpler logging) | Resume keyword + real skill |

---

## Phase 0 — System Design & Planning

**Goal:** Decide architecture before writing code.

- Choose multi-tenancy strategy: **shared database, shared schema, with `tenant_id` + Row-Level Security (RLS)** — most realistic, most interview-relevant (vs. schema-per-tenant or db-per-tenant)
- Draw ERD: tenants, users, roles, projects, tasks, comments, subscriptions, audit_logs
- Define core entities and relationships
- Write a 1-page system design doc (this becomes a resume/interview artifact)

**Deliverable:** ERD diagram + architecture decision doc (markdown)

---

## Phase 1 — Tenant Isolation & Core Infrastructure

**Goal:** The non-negotiable foundation of any multi-tenant system.

**Build:**
- `tenants`, `users`, `roles`, `user_roles` tables
- Every tenant-scoped table carries a `tenant_id` column
- Enable **PostgreSQL Row-Level Security (RLS)** policies so a query can never leak cross-tenant data, even by accident
- Set `tenant_id` per request using a session variable (`SET app.current_tenant_id`) via a Spring interceptor/filter
- Soft-delete pattern (`deleted_at` instead of hard deletes) across all tables

**Concepts learned:** RLS policies, connection-level session variables, indexing strategy for multi-tenant queries (composite indexes starting with `tenant_id`)

**Deliverable:** Working tenant isolation — verified with a test that proves Tenant A literally cannot query Tenant B's data even with a broken `WHERE` clause.

---

## Phase 2 — Authentication & Authorization

**Goal:** Secure, realistic SaaS auth — not just login/signup.

**Build:**
- JWT-based auth with **access + refresh tokens**
- Tenant-aware login (user belongs to one or more tenants — support "switch workspace" like Slack/Notion)
- **RBAC** (Admin, Manager, Member, Viewer) enforced at API layer
- API key generation for programmatic/external access (per tenant) — shows you understand B2B SaaS auth patterns
- Password reset, email verification flows

**Concepts learned:** Stateless auth, refresh token rotation, RBAC vs ABAC, secure API key hashing/storage

**Deliverable:** Full auth flow + Postman/REST client collection demonstrating role-restricted endpoints.

---

## Phase 3 — Core Domain: Projects & Tasks

**Goal:** The actual product functionality.

**Build:**
- CRUD for Projects, Tasks, Subtasks, Comments, Labels, Attachments (metadata only)
- Task states (backlog → in progress → done) with state-transition validation
- Assign tasks to users, due dates, priorities
- Pagination, filtering, sorting (cursor-based pagination — more scalable than offset, good talking point)
- Full-text search over task titles/descriptions (Postgres `tsvector`)

**Concepts learned:** Cursor pagination, full-text search, optimistic locking (`version` column to prevent race conditions on concurrent edits)

**Deliverable:** Working REST API for the core product, documented with OpenAPI/Swagger.

---

## Phase 4 — Billing & Subscription Management

**Goal:** This is what makes it a *SaaS*, not just an app.

**Build:**
- Plans table (Free, Pro, Enterprise) with limits (seats, projects, storage, API calls/month)
- Usage tracking table (metered usage — API calls, storage used)
- Enforce plan limits at the application layer (reject requests once over quota)
- Stripe integration (test mode) for subscription checkout + webhooks (subscription created/cancelled/payment failed)
- Invoice/history table

**Concepts learned:** Usage-based billing patterns, idempotent webhook handling, event-driven state updates

**Deliverable:** A tenant can upgrade/downgrade plans, and limits are actually enforced.

---

## Phase 5 — Audit Logging & Compliance

**Goal:** Enterprise-readiness — the thing that separates toy projects from SaaS-grade ones.

**Build:**
- `audit_logs` table: who did what, when, on which resource (immutable, append-only)
- Use Postgres triggers OR application-level event hooks to auto-log critical actions (role changes, deletions, billing changes)
- Admin-facing endpoint to query audit trail (filterable by user/date/action)
- Data export endpoint (GDPR-style "export my tenant's data")

**Concepts learned:** Append-only audit design, triggers vs. application-level logging trade-offs, compliance-driven schema design

**Deliverable:** Tamper-evident audit trail, queryable per tenant.

---

## Phase 6 — AI Layer (Differentiator Phase)

**Goal:** This is the phase that makes the project stand out in 2026's job market. Built as a separate Python/FastAPI microservice talking to your Java backend.

**Build (pick 3–4, don't do all — depth over breadth):**
1. **Semantic search** — embed task titles/descriptions using an embeddings API, store vectors in Postgres via `pgvector`, enable "find similar tasks" / natural-language search
2. **AI task summarizer** — auto-generate a daily/weekly digest of a project's activity using an LLM
3. **Smart auto-tagging** — LLM classifies incoming tasks into labels/priority automatically
4. **AI support assistant (RAG)** — chatbot that answers "how do I do X in this workspace" by retrieving from your own docs/audit logs (mini-RAG pipeline)
5. **Anomaly detection** — flag unusual usage patterns (e.g., a user suddenly exporting huge amounts of data) — ties back into your audit logs

**Concepts learned:** Embeddings, vector similarity search, RAG pipeline basics, microservice-to-microservice auth (service-to-service JWT or mTLS), prompt design for structured output

**Deliverable:** At least one working AI feature, end-to-end, with a clear before/after demo.

---

## Phase 7 — Real-Time Collaboration

**Goal:** Show you understand more than request/response APIs.

**Build:**
- WebSocket connection for live task updates (when User A moves a task, User B sees it instantly)
- Presence indicators ("who's online in this project")
- Notifications system (in-app + optionally email via async queue)

**Concepts learned:** WebSocket auth, pub/sub via Redis to fan out events across server instances (important once you mention "horizontal scaling")

**Deliverable:** Two browser tabs, different users, live-updating board.

---

## Phase 8 — Observability, Caching & Performance

**Goal:** Prove you think about production, not just features.

**Build:**
- Redis caching for expensive/frequent reads (e.g., dashboard stats)
- Rate limiting per tenant (token bucket via Redis) — prevents one tenant from starving others, classic multi-tenant concern
- Structured logging (JSON logs) with request tracing IDs
- Basic metrics dashboard (Prometheus + Grafana, or even a simple custom one) — track request latency, error rate, active tenants
- `EXPLAIN ANALYZE` review + index tuning on your slowest queries

**Concepts learned:** Rate limiting algorithms, caching invalidation strategies, query optimization, basic observability stack

**Deliverable:** A short "before/after" performance write-up showing a query you optimized (great interview story material).

---

## Phase 9 — DevOps & Deployment

**Goal:** Ship it like a real product.

**Build:**
- Dockerize backend, AI microservice, and frontend
- `docker-compose` for local dev (Postgres + Redis + backend + AI service + frontend)
- GitHub Actions CI/CD: run tests → build → deploy on push to main
- Deploy to a free/cheap tier (Railway, Render, or Fly.io) for a live demo link

**Concepts learned:** Containerization, CI/CD pipelines, environment-based config management

**Deliverable:** A live, public demo URL — this alone makes the project 10x more credible to recruiters.

---

## Phase 10 — Resume Packaging & Documentation

**Goal:** Make sure the work actually lands in interviews.

**Build:**
- Clean README: problem statement, architecture diagram, tech stack, key engineering decisions, screenshots/GIF demo
- A short "Engineering Decisions" doc explaining trade-offs (e.g., "why RLS over schema-per-tenant," "why cursor pagination")
- 2–3 minute Loom/demo video walking through the AI feature + multi-tenant isolation proof
- One-paragraph resume bullet + a 60-second "tell me about a project" interview pitch, pre-written and rehearsed

**Deliverable:** A GitHub repo + live demo + talking points you can recite cold in an interview.

---

## What Makes This Stand Out (Summary)

- **Real multi-tenancy** with Postgres RLS — most student projects fake this with a `WHERE tenant_id =` clause and call it done
- **Usage-based billing** — most projects skip this entirely; it shows product + engineering thinking
- **Audit logging** — enterprise/compliance signal, rare in portfolio projects
- **AI layer with RAG/embeddings** — the single most resume-relevant addition in 2026's hiring market
- **Rate limiting per tenant** — a genuinely hard, real-world distributed systems problem
- **Live deployed demo** — most candidates only have a GitHub link; you'll have a working product

---

## Suggested Pacing

Given your existing roadmap structure, treat this as a parallel track:
- Phases 0–3: ~3 weeks (core build)
- Phase 4–5: ~1.5 weeks (billing + audit)
- Phase 6: ~1.5–2 weeks (AI layer — most resume value per hour spent)
- Phase 7–8: ~1.5 weeks
- Phase 9–10: ~1 week (deployment + packaging)

**Total: ~8–9 weeks at a steady pace, less if you timebox aggressively.**
