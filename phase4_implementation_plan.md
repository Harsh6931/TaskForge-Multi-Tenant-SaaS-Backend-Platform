# Phase 4 — Billing & Subscriptions
### Implementation Breakdown (Small Learning Goals)

> **Current state (entering Phase 4):** Phase 3 is fully complete. We have:
> - Full CRUD for Projects, Tasks, Comments, Labels — all tenant-scoped and RLS-guarded
> - Task status state machine (`BACKLOG → IN_PROGRESS → DONE`) with invalid-transition rejection
> - Optimistic locking on Tasks via `version` column — returns 409 Conflict on stale writes
> - Cursor-based pagination on `GET /projects/{id}/tasks` with Base64-encoded composite cursor
> - Full-text search via `tsvector` GENERATED column + GIN index + `plainto_tsquery`
> - `@PreAuthorize`-based role enforcement on every endpoint
> - `GlobalExceptionHandler` returning RFC 7807 `ProblemDetail` for 400/401/403/404/409/422

> **Phase 4 Goal:** Add a real billing layer. Every tenant operates under a `Plan` with hard limits. When a tenant exceeds those limits, the system returns `402 Payment Required` — not a silent failure. Upgrading via Stripe test checkout lifts the block. By the end you will understand: plan-gated feature enforcement, idempotent webhook handling, Stripe event verification, and subscription lifecycle management.

> **🧠 Owner note (you must complete before implementing):**
> - The exact plan limits (seats / projects / storage / API calls) for FREE, PRO, ENTERPRISE **must be decided by you** and written into `AGENTS.md`'s SCHEMA.md `plans` seed data section. Do NOT let the agent invent numbers — you defend these in interviews.
> - If you haven't filled in `docs/RATE_LIMITS.md` from Phase 8 planning yet, that's fine — Phase 4 only uses `max_projects`, `max_seats`, `max_api_calls_month`.

---

## 🗂️ Overview Map

```
Goal 1 → Understand billing architecture: plans, subscriptions, usage, Stripe webhooks
Goal 2 → Flyway migration V9: plans, subscriptions, usage_records tables + seed data
Goal 3 → Plan, Subscription, UsageRecord entities + repositories
Goal 4 → BillingService — plan lookup, subscription management
Goal 5 → UsageGuard — the enforcement layer (the core Phase 4 concept)
Goal 6 → Wire UsageGuard into ProjectService and TaskService
Goal 7 → Stripe integration — checkout session creation
Goal 8 → Stripe webhook handler — idempotent event processing
Goal 9 → BillingController — expose billing endpoints
Goal 10 → Extend GlobalExceptionHandler for billing exceptions
Goal 11 → Write the full test suite
```

---

## Goal 1 — Understand the Billing Architecture (Mental Model First)

> **Why first?** Billing is the part of the codebase most likely to cause production incidents. Every concept — plan limits, subscription states, webhook idempotency — has a specific reason it works the way it does. Understand these before writing a single line.

### How the plan → subscription → tenant chain works

```
plans table            subscriptions table         tenants table
─────────────          ───────────────────         ─────────────
id (uuid)              id (uuid)                   id (uuid)
name = "FREE"    ◄──── plan_id (fk)                plan_id (fk) ──────► plans
max_projects = 3        tenant_id (fk)  ──────────► (tenants)
max_seats = 5           status = ACTIVE
max_api_calls = 1000    stripe_subscription_id
price_cents = 0         current_period_start
                        current_period_end
```

- A **Plan** is a template: it defines limits and price. Plans are seeded once, never created by users.
- A **Subscription** is a live instance: it links one `tenant` to one `plan`, and carries the Stripe subscription ID and billing period dates.
- A **Tenant** has a `plan_id` FK — this is a denormalized shortcut for fast plan-limit reads. It's kept in sync by the subscription update logic.

### The three subscription states you must know

```
ACTIVE     → tenant is paying (or on FREE). All usage is permitted within plan limits.
PAST_DUE   → payment failed but Stripe gives a grace period. Access may continue.
CANCELLED  → subscription ended. Tenant falls back to FREE limits (or is blocked).
```

> **Interview talking point:**
> *"When a Stripe `invoice.payment_failed` webhook arrives, I set the subscription to `PAST_DUE`. I don't revoke access immediately — Stripe retries payments for several days. If `customer.subscription.deleted` arrives, I set status to `CANCELLED` and downgrade the tenant's `plan_id` back to the FREE plan. All webhook handlers are idempotent — they check whether the Stripe event ID has already been processed before applying changes."*

### How UsageGuard works

```
Client: POST /projects  { name: "New project" }
                │
                ▼
        ProjectService.createProject()
                │
                ├─► usageGuard.checkProjectLimit(tenantId)
                │           │
                │           ├─► Load tenant's current Plan via subscription
                │           ├─► COUNT(projects) WHERE tenant_id = ? AND deleted_at IS NULL
                │           ├─► if count >= plan.maxProjects → throw PlanLimitExceededException
                │           └─► else → proceed
                │
                ▼
        projectRepository.save(project)   ← only reached if within limits
```

This is a **pre-condition check** — enforcement happens before any DB write. The guard doesn't look at a cached counter; it does a live COUNT. This is slightly expensive but guarantees correctness, even when multiple requests race.

### Why Stripe webhook handling must be idempotent

```
Stripe sends webhook → your server processes it → response 200
                                   │
                         Network timeout or 5xx
                                   │
Stripe retries the SAME webhook → your server must NOT double-process it
```

- Stripe guarantees **at-least-once** delivery — the same event can arrive multiple times.
- Your handler must be **exactly-once** in effect: store the `stripe_event_id` on first process, skip on duplicate.
- Pattern: `if (alreadyProcessed(event.getId())) return; processEvent(event); markProcessed(event.getId());`

---

## Goal 2 — Flyway Migration V9: Tables + Seed Data

> **Why migration before entities?** The same bottom-up discipline as every prior phase. Schema first, then Java.

### Create `V9__billing.sql`

```sql
-- ─────────────────────────────────────────────────────────────────
-- plans — the template tiers (seeded, not user-created)
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS plans (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(50)  NOT NULL UNIQUE,
    max_seats            INT          NOT NULL,
    max_projects         INT          NOT NULL,
    max_storage_mb       INT          NOT NULL,
    max_api_calls_month  INT          NOT NULL,
    price_cents          INT          NOT NULL   -- 0 for FREE
);

-- ─────────────────────────────────────────────────────────────────
-- subscriptions — a tenant's live billing relationship
-- ─────────────────────────────────────────────────────────────────
CREATE TYPE subscription_status AS ENUM ('ACTIVE', 'CANCELLED', 'PAST_DUE');

CREATE TABLE IF NOT EXISTS subscriptions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID         NOT NULL REFERENCES tenants(id),
    plan_id                 UUID         NOT NULL REFERENCES plans(id),
    status                  subscription_status NOT NULL DEFAULT 'ACTIVE',
    stripe_subscription_id  VARCHAR(255),            -- null for FREE plan
    current_period_start    TIMESTAMPTZ,
    current_period_end      TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_subscriptions_tenant_id ON subscriptions(tenant_id);

-- ─────────────────────────────────────────────────────────────────
-- usage_records — monthly usage snapshots per tenant per metric
-- ─────────────────────────────────────────────────────────────────
CREATE TYPE usage_metric AS ENUM ('API_CALLS', 'STORAGE_MB');

CREATE TABLE IF NOT EXISTS usage_records (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenants(id),
    metric       usage_metric NOT NULL,
    value        INT          NOT NULL DEFAULT 0,
    period_start TIMESTAMPTZ  NOT NULL,
    period_end   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_usage_records_tenant_metric ON usage_records(tenant_id, metric, period_start);

-- ─────────────────────────────────────────────────────────────────
-- processed_stripe_events — idempotency table
-- Webhook handler inserts event_id here before processing;
-- on duplicate, skips processing to guarantee exactly-once effect.
-- ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS processed_stripe_events (
    event_id     VARCHAR(255) PRIMARY KEY,   -- Stripe event ID, e.g. "evt_1Abc..."
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─────────────────────────────────────────────────────────────────
-- plan_id column on tenants (denormalized shortcut for fast reads)
-- ─────────────────────────────────────────────────────────────────
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS plan_id UUID REFERENCES plans(id);

-- ─────────────────────────────────────────────────────────────────
-- Seed Plans
-- !! FILL IN YOUR OWN NUMBERS BEFORE RUNNING !!
-- Replace the values below with the limits you decided in AGENTS.md
-- ─────────────────────────────────────────────────────────────────
INSERT INTO plans (name, max_seats, max_projects, max_storage_mb, max_api_calls_month, price_cents)
VALUES
    ('FREE',       5,   3,   512,    1000,  0      ),
    ('PRO',        20,  20,  5120,   50000, 1999   ),   -- $19.99/month
    ('ENTERPRISE', 100, 100, 51200,  500000,9999   );   -- $99.99/month
-- !! Change these numbers to match YOUR decisions from AGENTS.md !!
```

> **Why a `processed_stripe_events` table?** A `stripe_subscription_id` column on `subscriptions` tells you what Stripe subscription ID you're managing, but it doesn't tell you which webhook _events_ you've already processed. Those are separate concepts. The event table gives you a 1-row-per-event idempotency log that's trivially queryable and cheap to insert into.

> **Why `plan_id` on `tenants` as a denormalized column?** Every request goes through `TenantContextFilter` which resolves the tenant. If we had to join `subscriptions → plans` on every request to know the plan limits, we'd add a query to every API call. The denormalized `tenants.plan_id` lets `UsageGuard` fetch the plan with one cheap `tenants` lookup. We keep it in sync on subscription updates.

### Files to create
- `backend/src/main/resources/db/migration/V9__billing.sql`

---

## Goal 3 — Plan, Subscription, UsageRecord Entities + Repositories

> **Build the data layer before the logic layer.** The service classes all import these — you can't compile the service without the entity.

### 3a — Plan Entity

```java
// com/taskforge/billing/entity/Plan.java
@Entity
@Table(name = "plans")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;  // "FREE", "PRO", "ENTERPRISE"

    @Column(name = "max_seats", nullable = false)
    private int maxSeats;

    @Column(name = "max_projects", nullable = false)
    private int maxProjects;

    @Column(name = "max_storage_mb", nullable = false)
    private int maxStorageMb;

    @Column(name = "max_api_calls_month", nullable = false)
    private int maxApiCallsMonth;

    @Column(name = "price_cents", nullable = false)
    private int priceCents;
}
```

> **Why no `extends BaseEntity`?** Plans are not soft-deleted, have no `tenant_id`, and don't track `updatedAt` in the same lifecycle. They are seeded data — immutable reference rows, not user-owned resources. Use the lightest entity shape for the job.

### 3b — Subscription Entity

```java
// com/taskforge/billing/entity/Subscription.java
@Entity
@Table(name = "subscriptions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "tenant_id", insertable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false,
            columnDefinition = "subscription_status")
    private SubscriptionStatus status;

    @Column(name = "stripe_subscription_id")
    private String stripeSubscriptionId;  // null for FREE plan

    @Column(name = "current_period_start")
    private Instant currentPeriodStart;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}

// com/taskforge/billing/entity/SubscriptionStatus.java
public enum SubscriptionStatus {
    ACTIVE, CANCELLED, PAST_DUE
}
```

### 3c — UsageRecord Entity

```java
// com/taskforge/billing/entity/UsageRecord.java
@Entity
@Table(name = "usage_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false, columnDefinition = "usage_metric")
    private UsageMetric metric;

    @Column(name = "value", nullable = false)
    private int value;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;
}

// com/taskforge/billing/entity/UsageMetric.java
public enum UsageMetric {
    API_CALLS, STORAGE_MB
}
```

### 3d — ProcessedStripeEvent Entity

```java
// com/taskforge/billing/entity/ProcessedStripeEvent.java
@Entity
@Table(name = "processed_stripe_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ProcessedStripeEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 255)
    private String eventId;  // Stripe event ID as PK — natural key, no UUID

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    void prePersist() { processedAt = Instant.now(); }
}
```

> **Why use Stripe's `event_id` as the primary key directly?** Stripe event IDs are globally unique strings (e.g., `evt_1AbcXyz...`). Using them as the PK gives a natural uniqueness constraint at the DB level — if two threads race to process the same event, the second `INSERT` will throw a `DataIntegrityViolationException` (duplicate PK), which we catch and treat as "already processed." No separate `SELECT` before `INSERT` is needed — this is the **optimistic insert** pattern for idempotency.

### 3e — Repositories

```java
// com/taskforge/billing/repository/PlanRepository.java
@Repository
public interface PlanRepository extends JpaRepository<Plan, UUID> {
    Optional<Plan> findByName(String name);  // used to fetch "FREE" plan during downgrade
}

// com/taskforge/billing/repository/SubscriptionRepository.java
@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findByTenantIdAndStatus(UUID tenantId, SubscriptionStatus status);

    Optional<Subscription> findByStripeSubscriptionId(String stripeSubscriptionId);
    // ↑ used in webhook handler to look up which tenant to update
}

// com/taskforge/billing/repository/UsageRecordRepository.java
@Repository
public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    Optional<UsageRecord> findByTenantIdAndMetricAndPeriodStartLessThanEqualAndPeriodEndGreaterThan(
        UUID tenantId, UsageMetric metric, Instant periodStart, Instant periodEnd
    );
    // This long-named method finds the usage record covering "now" for a given metric.
    // Spring Data derives the query from the method name — no @Query needed.
}

// com/taskforge/billing/repository/ProcessedStripeEventRepository.java
@Repository
public interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {
    boolean existsByEventId(String eventId);
}
```

### Files to create
- `com/taskforge/billing/entity/Plan.java`
- `com/taskforge/billing/entity/Subscription.java`
- `com/taskforge/billing/entity/SubscriptionStatus.java`
- `com/taskforge/billing/entity/UsageRecord.java`
- `com/taskforge/billing/entity/UsageMetric.java`
- `com/taskforge/billing/entity/ProcessedStripeEvent.java`
- `com/taskforge/billing/repository/PlanRepository.java`
- `com/taskforge/billing/repository/SubscriptionRepository.java`
- `com/taskforge/billing/repository/UsageRecordRepository.java`
- `com/taskforge/billing/repository/ProcessedStripeEventRepository.java`

---

## Goal 4 — BillingService

> **Scope of BillingService:** Handles plan queries, subscription reads, and subscription updates driven by webhook events. It does NOT contain enforcement logic — that's `UsageGuard` (Goal 5). Separation of concerns: `BillingService` manages _state_, `UsageGuard` enforces _limits_.

### 4a — BillingService (plan + subscription management)

```java
// com/taskforge/billing/BillingService.java
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class BillingService {

    // ── getActivePlanForTenant(UUID tenantId) → Plan ─────────────────────────
    // 1. Load Tenant by tenantId (throw ResourceNotFoundException if not found)
    // 2. If tenant.getPlanId() == null → assign FREE plan, save tenant
    // 3. Load Plan by tenant.getPlanId()
    // 4. Return Plan
    // NOTE: This is the fast path used by UsageGuard on every request.
    //       One SELECT on tenants + one SELECT on plans = two cheap PK lookups.

    // ── listAllPlans() → List<PlanResponse> ──────────────────────────────────
    // 1. planRepository.findAll()
    // 2. Map to PlanResponse (all fields including priceCents)
    // 3. Return list — this powers the public GET /billing/plans endpoint

    // ── getActiveSubscription(UUID tenantId) → Optional<Subscription> ────────
    // 1. subscriptionRepository.findByTenantIdAndStatus(tenantId, ACTIVE)
    // 2. Return Optional — caller decides what to do if empty

    // ── onSubscriptionCreatedOrUpdated(String stripeSubId, String planName,    ──
    //                                   String status, Instant periodStart,
    //                                   Instant periodEnd, UUID tenantId)
    // Called by: StripeWebhookHandler (Goal 8)
    // 1. Look up plan by name (e.g., "PRO") — throw if not found
    // 2. Find existing subscription by stripeSubId OR by tenantId (upsert logic)
    // 3. Update status, plan, period dates on the subscription entity
    // 4. Save subscription
    // 5. Update tenant.setPlanId(plan.getId()) — keep denormalized FK in sync
    // 6. Save tenant

    // ── onSubscriptionCancelled(String stripeSubId) ───────────────────────────
    // Called by: StripeWebhookHandler on customer.subscription.deleted
    // 1. Find subscription by stripeSubscriptionId
    // 2. Set status = CANCELLED
    // 3. Save subscription
    // 4. Load FREE plan from planRepository.findByName("FREE")
    // 5. Update tenant.setPlanId(freePlan.getId())  ← downgrade on cancellation
    // 6. Save tenant
    // 7. Log: log.info("Tenant {} downgraded to FREE after cancellation", tenantId)

    // ── onPaymentFailed(String stripeSubId) ───────────────────────────────────
    // Called by: StripeWebhookHandler on invoice.payment_failed
    // 1. Find subscription by stripeSubscriptionId
    // 2. Set status = PAST_DUE
    // 3. Save subscription
    // NOTE: Do NOT change tenant.plan_id on payment failure — Stripe retries.
    //       Only downgrade on customer.subscription.deleted.
}
```

### 4b — BillingService DTOs

```java
// com/taskforge/billing/dto/PlanResponse.java
record PlanResponse(
    UUID id,
    String name,
    int maxSeats,
    int maxProjects,
    int maxStorageMb,
    int maxApiCallsMonth,
    int priceCents
) {
    public static PlanResponse from(Plan plan) { ... }
}

// com/taskforge/billing/dto/SubscriptionResponse.java
record SubscriptionResponse(
    UUID id,
    UUID tenantId,
    String planName,
    String status,          // "ACTIVE", "CANCELLED", "PAST_DUE"
    Instant currentPeriodStart,
    Instant currentPeriodEnd
) {
    public static SubscriptionResponse from(Subscription sub) { ... }
}

// com/taskforge/billing/dto/CreateCheckoutSessionRequest.java
record CreateCheckoutSessionRequest(
    @NotNull UUID planId
) {}

// com/taskforge/billing/dto/CheckoutSessionResponse.java
record CheckoutSessionResponse(
    String checkoutUrl  // Stripe-generated URL — redirect the frontend here
) {}
```

### Files to create
- `com/taskforge/billing/BillingService.java`
- `com/taskforge/billing/dto/PlanResponse.java`
- `com/taskforge/billing/dto/SubscriptionResponse.java`
- `com/taskforge/billing/dto/CreateCheckoutSessionRequest.java`
- `com/taskforge/billing/dto/CheckoutSessionResponse.java`

---

## Goal 5 — UsageGuard (The Core Phase 4 Concept)

> **Why its own class?** `UsageGuard` is an enforcement layer that cuts across multiple services (ProjectService, TaskService, and later API rate limiting in Phase 8). If you embed this logic inside each service, you'll have duplicated limit-checking code scattered across the codebase. A single `@Service` class that all other services call is the correct design — it's the **Guard Pattern** (similar to Spring's `@PreAuthorize`, but for business-rule limits instead of security roles).

### 5a — PlanLimitExceededException

```java
// com/taskforge/common/exception/PlanLimitExceededException.java
// HTTP status: 402 Payment Required
public class PlanLimitExceededException extends RuntimeException {

    private final String limitType;  // "projects", "seats", "api_calls"
    private final int current;
    private final int maximum;

    public PlanLimitExceededException(String limitType, int current, int maximum) {
        super(String.format(
            "Plan limit exceeded for '%s': current=%d, maximum=%d. " +
            "Upgrade your plan to increase this limit.",
            limitType, current, maximum
        ));
        this.limitType = limitType;
        this.current = current;
        this.maximum = maximum;
    }

    // Getters for limitType, current, maximum — used by GlobalExceptionHandler
    // to build a rich 402 response body with upgrade context.
}
```

### 5b — UsageGuard

```java
// com/taskforge/billing/UsageGuard.java
@Service
@RequiredArgsConstructor
@Slf4j
public class UsageGuard {

    private final BillingService billingService;
    private final ProjectRepository projectRepository;
    private final TenantUserRepository tenantUserRepository;

    // ── checkProjectLimit(UUID tenantId) ─────────────────────────────────────
    // Called by: ProjectService.createProject() BEFORE saving
    //
    // 1. Load Plan via billingService.getActivePlanForTenant(tenantId)
    // 2. long currentProjects = projectRepository.countByTenantIdAndDeletedAtIsNull(tenantId)
    // 3. if (currentProjects >= plan.getMaxProjects()) {
    //        throw new PlanLimitExceededException("projects", (int)currentProjects, plan.getMaxProjects());
    //    }
    // 4. (else) return quietly — ProjectService continues to save

    // ── checkSeatLimit(UUID tenantId) ────────────────────────────────────────
    // Called by: TenantService.inviteUser() BEFORE adding member
    //
    // 1. Load Plan via billingService.getActivePlanForTenant(tenantId)
    // 2. long currentSeats = tenantUserRepository.countByTenantId(tenantId)
    // 3. if (currentSeats >= plan.getMaxSeats()) {
    //        throw new PlanLimitExceededException("seats", (int)currentSeats, plan.getMaxSeats());
    //    }

    // ── checkApiCallLimit(UUID tenantId) ─────────────────────────────────────
    // Called by: (Phase 8 rate limiter or a per-request filter if eager enforcement is needed)
    // Deferred to Phase 8 — stub method here, no-op implementation for now.
    //
    // Full implementation in Phase 8:
    //   1. Load Plan → plan.getMaxApiCallsMonth()
    //   2. Load current month's UsageRecord for metric=API_CALLS
    //   3. if usageRecord.getValue() >= plan.getMaxApiCallsMonth() → throw 402

    // ── recordApiCall(UUID tenantId) ─────────────────────────────────────────
    // Increments the API_CALLS usage counter for the current billing period.
    // Implementation deferred to Phase 8 — stub here.
}
```

> **Why `countByTenantIdAndDeletedAtIsNull` and not just `countByTenantId`?** The project table uses soft-delete — `deleted_at` is set instead of hard deletion. Without the `deletedAtIsNull` condition, deleted projects would count against the limit, letting tenants never reclaim quota by deleting projects. Always filter soft-deleted rows in aggregate queries.

> **Interview talking point:**
> *"My `UsageGuard` runs a live COUNT before every project creation — not a cached counter. This guarantees correctness even under concurrent requests. Yes, it's slightly more expensive than a counter, but plan enforcement errors (letting tenants exceed limits silently) are far worse than a slightly slower write path. I could add a Redis counter in Phase 8 for high-throughput API call tracking without sacrificing correctness for the rarer project creation flow."*

### Files to create
- `com/taskforge/common/exception/PlanLimitExceededException.java`
- `com/taskforge/billing/UsageGuard.java`

### Files to modify
- `com/taskforge/project/repository/ProjectRepository.java` — add `countByTenantIdAndDeletedAtIsNull`
- `com/taskforge/tenant/repository/TenantUserRepository.java` — add `countByTenantId`

---

## Goal 6 — Wire UsageGuard into ProjectService and TaskService

> **Why a separate goal?** "Wiring" is a deliberate step — you need to trace exactly where the guard is called and confirm it's called _before_ any side effects (saves, emails, etc.). This is easy to get wrong by calling it after `save()` accidentally.

### 6a — ProjectService modification

```java
// MODIFY ProjectService.createProject():

public ProjectResponse createProject(UUID tenantId, UUID userId, CreateProjectRequest request) {

    // ── 1. USAGE GUARD — must be BEFORE any repository call ──────────────────
    usageGuard.checkProjectLimit(tenantId);
    // If limit exceeded → PlanLimitExceededException is thrown here → 402 returned
    // Execution does NOT continue past this line if limit is hit.

    // ── 2. Load tenant + user (existing logic) ────────────────────────────────
    Tenant tenant = tenantRepository.findById(tenantId)
        .orElseThrow(() -> new ResourceNotFoundException("Tenant", tenantId));
    User creator = userRepository.findById(userId)
        .orElseThrow(() -> new ResourceNotFoundException("User", userId));

    // ── 3. Build and save project (existing logic) ────────────────────────────
    Project project = Project.builder()
        .tenant(tenant)
        .name(request.name())
        .description(request.description())
        .createdBy(creator)
        .build();

    return ProjectResponse.from(projectRepository.save(project));
}
```

> **Why the guard is first:** Any code before the guard could have side effects (logging, loading objects, even just touching the DB). If the guard throws, all work before it was wasted. Call the guard as the **very first line** of every method it protects.

### 6b — TenantService modification (seat limit)

```java
// MODIFY TenantService.inviteUser() (or addMember() — whatever your Phase 1/2 method is named):

public void inviteUser(UUID tenantId, UUID inviteeUserId, TenantUserRole role) {

    // ── 1. USAGE GUARD — seat check ───────────────────────────────────────────
    usageGuard.checkSeatLimit(tenantId);

    // ── 2. Existing invite logic ... ──────────────────────────────────────────
}
```

### 6c — Confirm no TaskService guard is needed

Per SCHEMA.md, `plans` table has `max_projects` but **not** `max_tasks`. Tasks have no per-plan limit — once you have a project, you can create unlimited tasks within it. Do NOT add an unnecessary task count guard.

> **Why no task limit?** Most SaaS products (Jira, Linear, Asana) limit projects/seats, not individual tasks. Task limits would frustrate users for minimal business benefit. Your interview answer: *"I only enforce limits where they have business meaning — projects, seats, and API calls. I deliberately chose not to limit tasks per project, because that would be punitive and not aligned with how project management tools typically monetize."*

### Files to modify
- `com/taskforge/project/ProjectService.java` — inject `UsageGuard`, call `checkProjectLimit` first
- `com/taskforge/tenant/TenantService.java` — inject `UsageGuard`, call `checkSeatLimit` in invite flow

---

## Goal 7 — Stripe Integration: Checkout Session Creation

> **Why Stripe test mode only?** You are building for a portfolio/interview, not for real payments. Stripe test mode gives you real webhook infrastructure, test card numbers, and all the same APIs — without touching real money.

### 7a — Add Stripe dependency to pom.xml

```xml
<!-- Add to backend/pom.xml <dependencies> -->
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>stripe-java</artifactId>
    <version>25.3.0</version>  <!-- Check for latest stable at time of build -->
</dependency>
```

### 7b — Stripe configuration

```yaml
# Add to backend/src/main/resources/application.yml:
stripe:
  secret-key: ${STRIPE_SECRET_KEY}         # set via env var — never hardcode
  webhook-secret: ${STRIPE_WEBHOOK_SECRET} # from Stripe dashboard webhook settings
  success-url: ${STRIPE_SUCCESS_URL:http://localhost:5173/billing/success}
  cancel-url: ${STRIPE_CANCEL_URL:http://localhost:5173/billing/cancel}
```

```java
// com/taskforge/config/StripeConfig.java
@Configuration
@ConfigurationProperties(prefix = "stripe")
@Getter @Setter
public class StripeConfig {

    private String secretKey;
    private String webhookSecret;
    private String successUrl;
    private String cancelUrl;

    @PostConstruct
    public void init() {
        // Set the Stripe SDK API key globally at startup
        Stripe.apiKey = secretKey;
    }
}
```

> **Why `@PostConstruct` instead of setting the key in each service method?** `Stripe.apiKey` is a static field on the Stripe SDK. Setting it once at application startup is the correct pattern — the SDK uses it globally for every API call. Setting it per-request would be redundant and potentially race-prone in a multi-threaded environment.

### 7c — StripeCheckoutService

```java
// com/taskforge/billing/StripeCheckoutService.java
@Service
@RequiredArgsConstructor
@Slf4j
public class StripeCheckoutService {

    private final StripeConfig stripeConfig;
    private final PlanRepository planRepository;

    // ── createCheckoutSession(UUID planId, UUID tenantId) → String checkoutUrl ──
    //
    // 1. Load Plan by planId (throw 404 if not found)
    // 2. Validate plan is not FREE (FREE plan has no Stripe checkout)
    //    if (plan.getPriceCents() == 0) throw new IllegalArgumentException(
    //        "Cannot create checkout session for FREE plan")
    //
    // 3. Build Stripe price data inline (no pre-created Stripe Price IDs needed for POC):
    //    SessionCreateParams params = SessionCreateParams.builder()
    //        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
    //        .setSuccessUrl(stripeConfig.getSuccessUrl() + "?session_id={CHECKOUT_SESSION_ID}")
    //        .setCancelUrl(stripeConfig.getCancelUrl())
    //        .addLineItem(
    //            SessionCreateParams.LineItem.builder()
    //                .setQuantity(1L)
    //                .setPriceData(
    //                    SessionCreateParams.LineItem.PriceData.builder()
    //                        .setCurrency("usd")
    //                        .setUnitAmount((long) plan.getPriceCents())
    //                        .setRecurring(
    //                            SessionCreateParams.LineItem.PriceData.Recurring.builder()
    //                                .setInterval(
    //                                    SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
    //                                .build()
    //                        )
    //                        .setProductData(
    //                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
    //                                .setName("TaskForge " + plan.getName())
    //                                .build()
    //                        )
    //                        .build()
    //                )
    //                .build()
    //        )
    //        .putMetadata("tenant_id", tenantId.toString())
    //        .putMetadata("plan_name", plan.getName())
    //        .build();
    //
    // 4. Session session = Session.create(params)  ← Stripe API call
    // 5. Return session.getUrl()
    //
    // Wrap StripeException → throw new RuntimeException("Stripe API error", e)
    //   (GlobalExceptionHandler can catch RuntimeException → 500 with Stripe error detail)
}
```

> **Why store `tenant_id` + `plan_name` in Stripe metadata?** When Stripe sends `customer.subscription.created` webhook, the payload contains the Stripe subscription ID but not your internal tenant ID. Storing your context in Stripe's `metadata` field during checkout means the webhook handler can read `event.data.object.metadata.tenant_id` and know exactly which tenant to update — no extra DB lookup needed.

### Files to create
- `com/taskforge/config/StripeConfig.java`
- `com/taskforge/billing/StripeCheckoutService.java`

### Files to modify
- `backend/pom.xml` — add Stripe dependency
- `backend/src/main/resources/application.yml` — add `stripe.*` config block

---

## Goal 8 — Stripe Webhook Handler (Idempotent)

> **Why the most critical goal?** This is where money changes hands. A bug here means either: tenants get upgraded without paying (revenue loss), or tenants don't get upgraded after paying (support tickets, chargebacks). Both are bad. The idempotency pattern here is the primary interview talking point for this phase.

### 8a — StripeWebhookHandler

```java
// com/taskforge/billing/StripeWebhookHandler.java
@Component
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookHandler {

    private final StripeConfig stripeConfig;
    private final BillingService billingService;
    private final ProcessedStripeEventRepository processedEventRepo;

    // ── handleEvent(String payload, String sigHeader) ─────────────────────────
    // Called by BillingController.stripeWebhook()
    //
    // STEP 1: Verify Stripe signature
    //   try {
    //       Event event = Webhook.constructEvent(payload, sigHeader, stripeConfig.getWebhookSecret());
    //   } catch (SignatureVerificationException e) {
    //       log.warn("Invalid Stripe signature");
    //       throw new InvalidStripeSignatureException();  // → 400
    //   }
    //
    // STEP 2: Idempotency check — optimistic insert
    //   try {
    //       processedEventRepo.save(new ProcessedStripeEvent(event.getId()));
    //   } catch (DataIntegrityViolationException e) {
    //       // Duplicate PK = already processed
    //       log.info("Stripe event {} already processed, skipping", event.getId());
    //       return;  // 200 OK — tell Stripe we received it (don't retry)
    //   }
    //
    // STEP 3: Route to the correct handler method
    //   switch (event.getType()) {
    //       case "customer.subscription.created",
    //            "customer.subscription.updated" → handleSubscriptionUpsert(event)
    //       case "customer.subscription.deleted"  → handleSubscriptionCancelled(event)
    //       case "invoice.payment_failed"          → handlePaymentFailed(event)
    //       default → log.debug("Unhandled Stripe event type: {}", event.getType())
    //   }

    // ── handleSubscriptionUpsert(Event event) ────────────────────────────────
    // 1. Deserialize: Subscription sub = (Subscription) event.getDataObjectDeserializer()
    //                                       .getObject().orElseThrow()
    // 2. Extract: String tenantId = sub.getMetadata().get("tenant_id")
    //             String planName = sub.getMetadata().get("plan_name")
    //             SubscriptionStatus status = map Stripe status string to your enum
    //             Instant periodStart = Instant.ofEpochSecond(sub.getCurrentPeriodStart())
    //             Instant periodEnd   = Instant.ofEpochSecond(sub.getCurrentPeriodEnd())
    // 3. billingService.onSubscriptionCreatedOrUpdated(
    //         sub.getId(), planName, status.name(),
    //         periodStart, periodEnd, UUID.fromString(tenantId))

    // ── handleSubscriptionCancelled(Event event) ──────────────────────────────
    // 1. Deserialize Stripe Subscription object
    // 2. billingService.onSubscriptionCancelled(sub.getId())

    // ── handlePaymentFailed(Event event) ─────────────────────────────────────
    // 1. Deserialize Stripe Invoice object
    // 2. String stripeSubId = invoice.getSubscription()
    // 3. billingService.onPaymentFailed(stripeSubId)
}
```

> **The optimistic insert idempotency pattern explained:**
> ```
> Thread A: INSERT INTO processed_stripe_events (event_id) VALUES ('evt_123') → SUCCESS
> Thread B: INSERT INTO processed_stripe_events (event_id) VALUES ('evt_123') → DUPLICATE KEY → caught → skip
> ```
> This is superior to `SELECT ... IF NOT EXISTS ... INSERT` because it avoids a race window between the SELECT and INSERT. One atomic INSERT with PK violation is the correct approach.

### 8b — InvalidStripeSignatureException

```java
// com/taskforge/common/exception/InvalidStripeSignatureException.java
// HTTP status: 400 Bad Request
public class InvalidStripeSignatureException extends RuntimeException {
    public InvalidStripeSignatureException() {
        super("Stripe webhook signature verification failed");
    }
}
```

> **Why 400 for bad signature?** Stripe documentation says to return 400 for signature failures. Stripe interprets any non-2xx response as a failure and will retry the webhook. We return 400 to tell Stripe "this request was malformed" — as opposed to 500 (server error, retry) or 200 (accepted, don't retry). We want Stripe to NOT retry events with invalid signatures.

### Files to create
- `com/taskforge/billing/StripeWebhookHandler.java`
- `com/taskforge/common/exception/InvalidStripeSignatureException.java`

---

## Goal 9 — BillingController

> **Why its own goal?** The billing controller is unusual: the `/billing/webhook` endpoint must NOT be protected by JWT authentication (Stripe calls it, not users), must NOT parse the request body as JSON (Stripe signature is computed over the raw bytes), and must receive the raw request body as a `String`. This breaks several Spring MVC defaults and is worth keeping in a focused goal.

### 9a — BillingController

```java
// com/taskforge/billing/BillingController.java
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;
    private final StripeCheckoutService checkoutService;
    private final StripeWebhookHandler webhookHandler;

    // ── GET /billing/plans ────────────────────────────────────────────────────
    // Returns all plans — public, no auth required (anyone can view plans before signup)
    @GetMapping("/plans")
    public List<PlanResponse> listPlans() {
        return billingService.listAllPlans();
    }

    // ── GET /billing/subscription ─────────────────────────────────────────────
    // Returns the current tenant's active subscription + plan details
    @GetMapping("/subscription")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
    public SubscriptionResponse getSubscription(
        @AuthenticationPrincipal UUID userId
    ) {
        // Resolve tenantId from SecurityContext (same pattern as other controllers)
        UUID tenantId = SecurityContextHelper.getTenantId();
        return billingService.getActiveSubscription(tenantId)
            .map(SubscriptionResponse::from)
            .orElseThrow(() -> new ResourceNotFoundException("Subscription", tenantId));
    }

    // ── POST /billing/checkout-session ────────────────────────────────────────
    // Creates a Stripe checkout URL for upgrading to a paid plan
    @PostMapping("/checkout-session")
    @PreAuthorize("hasRole('ADMIN')")   // only ADMIN can change billing
    public CheckoutSessionResponse createCheckoutSession(
        @Valid @RequestBody CreateCheckoutSessionRequest request,
        @AuthenticationPrincipal UUID userId
    ) {
        UUID tenantId = SecurityContextHelper.getTenantId();
        String checkoutUrl = checkoutService.createCheckoutSession(request.planId(), tenantId);
        return new CheckoutSessionResponse(checkoutUrl);
    }

    // ── POST /billing/webhook ─────────────────────────────────────────────────
    // Stripe calls this — no JWT auth, raw body required for signature verification
    //
    // CRITICAL: Do NOT annotate with @PreAuthorize — Stripe has no JWT.
    // CRITICAL: @RequestBody String payload — raw string, not parsed JSON.
    //           Spring MVC reads the body once. If another filter reads it first,
    //           signature verification will fail on empty body. Use HttpServletRequest
    //           if needed, but the simplest approach is @RequestBody String.
    @PostMapping(value = "/webhook", consumes = "application/json")
    public ResponseEntity<Void> stripeWebhook(
        @RequestBody String payload,
        @RequestHeader("Stripe-Signature") String sigHeader
    ) {
        webhookHandler.handleEvent(payload, sigHeader);
        return ResponseEntity.ok().build();
    }
}
```

> **Why must `/billing/webhook` be excluded from JWT auth?** The `JwtAuthenticationFilter` and `ApiKeyAuthenticationFilter` from Phase 2 run on every request. Stripe does not send a JWT — it sends its own `Stripe-Signature` header. The webhook endpoint must be added to the security config's `permitAll()` list, just like `/auth/**`.

### 9b — SecurityConfig modification

```java
// MODIFY SecurityConfig.java — add /billing/webhook to permitAll:
.requestMatchers("/auth/**", "/billing/plans", "/billing/webhook").permitAll()
//                                              ↑ plans are public  ↑ Stripe webhook bypasses JWT
```

> **Why also make `/billing/plans` permit all?** Users browsing your pricing page before signing up should see plans without logging in. This is standard SaaS UX.

### Files to create
- `com/taskforge/billing/BillingController.java`

### Files to modify
- `com/taskforge/config/SecurityConfig.java` — add webhook + plans to `permitAll()`

---

## Goal 10 — Extend GlobalExceptionHandler

> **New exception types introduced in Phase 4:**

```java
// Add to GlobalExceptionHandler.java:

// ── 402 Payment Required — Plan Limit Exceeded ───────────────────────────
@ExceptionHandler(PlanLimitExceededException.class)
public ProblemDetail handlePlanLimitExceeded(PlanLimitExceededException ex) {
    log.info("Plan limit exceeded: {}", ex.getMessage());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.PAYMENT_REQUIRED, ex.getMessage());
    problem.setTitle("Plan Limit Exceeded");
    problem.setProperty("limitType", ex.getLimitType());
    problem.setProperty("current",   ex.getCurrent());
    problem.setProperty("maximum",   ex.getMaximum());
    problem.setProperty("upgradeUrl", "/billing/checkout-session");
    return problem;
}

// ── 400 Bad Request — Invalid Stripe Signature ───────────────────────────
@ExceptionHandler(InvalidStripeSignatureException.class)
public ProblemDetail handleInvalidStripeSignature(InvalidStripeSignatureException ex) {
    log.warn("Stripe signature verification failed");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setTitle("Invalid Webhook Signature");
    return problem;
}
```

> **Why include `upgradeUrl` in the 402 response body?** When the frontend receives a 402, it needs to know where to redirect the user to upgrade. Embedding the endpoint path in the `ProblemDetail` properties removes hardcoded frontend strings — the backend tells the client what to do. This is a small HATEOAS-adjacent design choice worth mentioning in interviews.

### Files to modify
- `com/taskforge/common/exception/GlobalExceptionHandler.java` — add two handlers above

---

## Goal 11 — Test Suite

> **Phase 4 testing philosophy:** Phase 4 has three distinct layers to test: (1) the UsageGuard logic, (2) the BillingService state transitions, and (3) the webhook handler's idempotency. Each has a different testing strategy.

### 11a — UsageGuardTest (Unit Test — the most critical)

```java
// src/test/java/com/taskforge/billing/UsageGuardTest.java
// @ExtendWith(MockitoExtension.class) — no Spring context

// Test cases:
// checkProjectLimit_belowLimit_doesNotThrow()
//   → Mock plan with maxProjects=3, countByTenantId returns 2 → no exception

// checkProjectLimit_atLimit_throwsPlanLimitExceededException()
//   → Mock plan with maxProjects=3, countByTenantId returns 3 → throws with limitType="projects"

// checkProjectLimit_aboveLimit_throwsPlanLimitExceededException()
//   → Mock plan with maxProjects=3, countByTenantId returns 5 → throws

// checkSeatLimit_belowLimit_doesNotThrow()
//   → Mock plan with maxSeats=5, tenantUserRepo count returns 4 → no exception

// checkSeatLimit_atLimit_throwsPlanLimitExceededException()
//   → Mock plan with maxSeats=5, tenantUserRepo count returns 5 → throws

// checkProjectLimit_exceptionContainsCorrectLimitValues()
//   → Verify ex.getCurrent()=3, ex.getMaximum()=3, ex.getLimitType()="projects"
```

### 11b — BillingServiceTest (Unit Test)

```java
// src/test/java/com/taskforge/billing/BillingServiceTest.java

// Test cases:
// onSubscriptionCancelled_setsCancelledStatus_andDowngradesToFreePlan()
//   → Mock subscription + FREE plan lookup → verify status=CANCELLED, tenant.planId=freePlan.id

// onPaymentFailed_setsPastDueStatus_doesNotChangePlan()
//   → Mock subscription → verify status=PAST_DUE, tenant.planId NOT changed

// onSubscriptionCreatedOrUpdated_updatesStatusAndPeriodDates()
//   → Verify subscription status, planId, periodStart, periodEnd all updated

// getActivePlanForTenant_tenantWithNullPlanId_assignsFreePlan()
//   → Tenant has null planId → verify FREE plan assigned + tenant saved
```

### 11c — StripeWebhookHandlerTest (Unit Test)

```java
// src/test/java/com/taskforge/billing/StripeWebhookHandlerTest.java

// Test cases:
// handleEvent_alreadyProcessedEvent_skipsProcessingAndReturns()
//   → Mock processedEventRepo.save() to throw DataIntegrityViolationException
//   → Verify billingService.onSubscriptionCreatedOrUpdated() is NEVER called

// handleEvent_newEvent_processesAndSavesEventId()
//   → Mock processedEventRepo.save() to succeed
//   → Verify billingService method IS called

// handleEvent_invalidSignature_throwsInvalidStripeSignatureException()
//   → Pass a malformed sigHeader → verify InvalidStripeSignatureException thrown
//   NOTE: Stripe signature verification requires the actual Stripe library internals.
//        Use an integration test with a real payload + test secret for this case,
//        or mock the Webhook.constructEvent static method with Mockito's mockStatic.
```

> **How to test Stripe signature verification without making real API calls?** Stripe provides test webhook secrets and a way to construct valid payloads locally via `stripe-cli trigger customer.subscription.created` + `stripe listen --forward-to localhost:8080/billing/webhook`. For unit tests, mock the static `Webhook.constructEvent` using Mockito's `mockStatic` or use the `stripe-java` test utilities.

### 11d — BillingControllerTest (Integration / MockMvc)

```java
// src/test/java/com/taskforge/billing/BillingControllerTest.java
// @WebMvcTest(BillingController.class)

// Test cases:
// GET /billing/plans → 200, returns list of plans (no auth required)
// POST /billing/checkout-session as ADMIN → 200 with checkoutUrl
// POST /billing/checkout-session as MEMBER → 403 (RBAC enforcement)
// POST /billing/webhook with valid payload → 200
// POST /billing/webhook with invalid Stripe-Signature header → 400
```

### 11e — ProjectService plan limit integration test

```java
// src/test/java/com/taskforge/project/ProjectServicePlanLimitTest.java
// @SpringBootTest + @Transactional

// Test cases:
// createProject_freePlanAtMaxProjects_returns402()
//   Full integration: seed FREE plan (maxProjects=3), create tenant on FREE,
//   create 3 projects → attempt 4th → expect PlanLimitExceededException
//   This test proves UsageGuard + ProjectService + plan seed data all work together.
```

### Files to create
- `src/test/java/com/taskforge/billing/UsageGuardTest.java`
- `src/test/java/com/taskforge/billing/BillingServiceTest.java`
- `src/test/java/com/taskforge/billing/StripeWebhookHandlerTest.java`
- `src/test/java/com/taskforge/billing/BillingControllerTest.java`
- `src/test/java/com/taskforge/project/ProjectServicePlanLimitTest.java`

---

## Phase 4 Completion Checklist

| Goal | Description | Status |
|---|---|---|
| Goal 1 | Billing architecture + idempotency mental model internalized | ⬜ |
| Goal 2 | V9 migration: plans, subscriptions, usage_records, processed_stripe_events tables + plan seed data | ⬜ |
| Goal 3 | Plan, Subscription, UsageRecord, ProcessedStripeEvent entities + repositories | ⬜ |
| Goal 4 | BillingService — plan queries + subscription state transitions | ⬜ |
| Goal 5 | UsageGuard — project limit + seat limit enforcement | ⬜ |
| Goal 6 | UsageGuard wired into ProjectService + TenantService | ⬜ |
| Goal 7 | Stripe dependency + config + StripeCheckoutService | ⬜ |
| Goal 8 | StripeWebhookHandler — idempotent event processing for 3 event types | ⬜ |
| Goal 9 | BillingController + SecurityConfig webhook exemption | ⬜ |
| Goal 10 | GlobalExceptionHandler extended for 402 + 400 Stripe errors | ⬜ |
| Goal 11 | Full test suite — UsageGuard unit + BillingService unit + webhook idempotency | ⬜ |

---

## Package Structure After Phase 4

```
com.taskforge
├── TaskForgeApplication.java
├── common/
│   ├── BaseEntity.java
│   ├── SoftDeleteService.java
│   └── exception/
│       ├── EmailAlreadyExistsException.java
│       ├── InvalidCredentialsException.java
│       ├── InvalidTokenException.java
│       ├── TenantAccessDeniedException.java
│       ├── ResourceNotFoundException.java
│       ├── InvalidStatusTransitionException.java
│       ├── OptimisticLockConflictException.java
│       ├── PlanLimitExceededException.java         ← NEW
│       ├── InvalidStripeSignatureException.java    ← NEW
│       └── GlobalExceptionHandler.java             ← MODIFIED
├── config/
│   ├── JpaConfig.java
│   ├── JwtProperties.java
│   ├── SecurityConfig.java                         ← MODIFIED (webhook permitAll)
│   ├── TenantConfig.java
│   └── StripeConfig.java                           ← NEW
├── auth/                              (Phase 2 — unchanged)
├── tenant/
│   └── TenantService.java             ← MODIFIED (seat guard wired in)
├── user/                              (Phase 1 — unchanged)
├── project/
│   ├── ProjectService.java            ← MODIFIED (project limit guard wired in)
│   └── repository/
│       └── ProjectRepository.java     ← MODIFIED (add countByTenantIdAndDeletedAtIsNull)
├── task/                              (Phase 3 — unchanged)
└── billing/                           ← NEW PACKAGE
    ├── entity/
    │   ├── Plan.java
    │   ├── Subscription.java
    │   ├── SubscriptionStatus.java
    │   ├── UsageRecord.java
    │   ├── UsageMetric.java
    │   └── ProcessedStripeEvent.java
    ├── repository/
    │   ├── PlanRepository.java
    │   ├── SubscriptionRepository.java
    │   ├── UsageRecordRepository.java
    │   └── ProcessedStripeEventRepository.java
    ├── dto/
    │   ├── PlanResponse.java
    │   ├── SubscriptionResponse.java
    │   ├── CreateCheckoutSessionRequest.java
    │   └── CheckoutSessionResponse.java
    ├── BillingService.java
    ├── UsageGuard.java
    ├── StripeCheckoutService.java
    ├── StripeWebhookHandler.java
    └── BillingController.java
```

---

## Key Concepts Mastered After Phase 4

| Concept | Where you used it |
|---|---|
| Plan-gated feature enforcement | `UsageGuard.checkProjectLimit()` + `checkSeatLimit()` |
| Soft-delete-aware COUNT queries | `countByTenantIdAndDeletedAtIsNull` in UsageGuard |
| Subscription lifecycle states (ACTIVE / PAST_DUE / CANCELLED) | `BillingService.onPaymentFailed()` + `onSubscriptionCancelled()` |
| Stripe checkout session creation with inline price data | `StripeCheckoutService.createCheckoutSession()` |
| Stripe metadata for tenant correlation | `putMetadata("tenant_id", ...)` in checkout, read back in webhook |
| Idempotent webhook handling via optimistic insert | `ProcessedStripeEvent` PK violation pattern in `StripeWebhookHandler` |
| Stripe signature verification | `Webhook.constructEvent(payload, sigHeader, secret)` |
| Denormalized FK for fast reads | `tenants.plan_id` kept in sync by `BillingService` |
| 402 Payment Required with structured ProblemDetail | `GlobalExceptionHandler.handlePlanLimitExceeded()` |
| Static Stripe SDK key initialization at startup | `StripeConfig.@PostConstruct` |
| Webhook endpoint excluded from JWT auth | `SecurityConfig.permitAll()` for `/billing/webhook` |

---

## 📏 Measurement Checkpoint (Phase 4 — collect after tests go green)

### New billing test count
```powershell
Select-String -Path "backend\src\test\**\*.java" -Pattern "@Test" -Recurse | Measure-Object
```
Record in `docs/METRICS_PLAYBOOK.md` § Metric 4.1 (total test count including Phase 4 additions)

### Verify plan seed data loaded correctly
```sql
SELECT name, max_projects, max_seats, max_api_calls_month, price_cents
FROM plans
ORDER BY price_cents;
-- Should return FREE (0), PRO, ENTERPRISE in ascending price order
```

### Verify 402 enforcement works end-to-end
Using your REST client (Bruno / Postman / curl):
1. Sign up a tenant on FREE plan
2. Create projects until you hit `max_projects`
3. Attempt one more `POST /projects` → confirm `HTTP 402` with body containing `limitType: "projects"`

### Verify idempotency
Send the same synthetic webhook payload twice (using `stripe-cli` or Postman):
- First call → 200, subscription updated
- Second call → 200, subscription NOT updated again (check DB `updated_at` didn't change)

Record in `docs/METRICS_PLAYBOOK.md` § Metric 4.2

---

## ⚠️ Things That Will Trip You Up in Phase 4

| Pitfall | Why it happens | How to avoid |
|---|---|---|
| Webhook returns 400 even with correct payload | Spring's `HttpMessageConverter` pre-reads the body for JSON parsing, leaving an empty stream for signature verification | Use `@RequestBody String payload` (raw String, not a DTO) — tells Spring not to parse it |
| `Stripe.apiKey` is null on first request | Config loaded but `@PostConstruct` not called | Ensure `StripeConfig` is a `@Configuration` bean (not a POJO), add `@PostConstruct` |
| Soft-deleted projects count against plan limit | Using `countByTenantId` without `deletedAt` filter | Always use `countByTenantIdAndDeletedAtIsNull` |
| Concurrent project creation bypasses limit | Two threads both read count=2 (below limit=3), both proceed, count becomes 4 | For a portfolio project, live COUNT is acceptable. Note in interview that a Redis atomic counter or DB-level CHECK constraint would handle this in production. |
| Duplicate Stripe event throws uncaught exception | `DataIntegrityViolationException` is a Spring exception — if not caught, it propagates as 500 | Wrap `processedEventRepo.save()` in try-catch for `DataIntegrityViolationException` |
| Plan metadata missing from webhook | Stripe subscription object doesn't have metadata if the checkout session metadata wasn't set | Confirm `putMetadata(...)` in `StripeCheckoutService` — and verify with `stripe-cli` before testing webhook handler |
