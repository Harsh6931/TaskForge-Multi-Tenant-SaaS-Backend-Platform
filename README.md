# TaskForge — Multi-Tenant Project Management Platform

Production-grade, AI-augmented multi-tenant SaaS platform built to demonstrate high-concurrency database design, robust tenant isolation, and modern microservices architecture.

---

## 🛡️ Multi-Tenant Data Isolation & RLS Verification Proof

TaskForge utilizes a **Shared Database + Shared Schema** architecture. Data isolation between tenants is enforced natively at the database engine level via **PostgreSQL Row-Level Security (RLS)**, ensuring zero cross-tenant data leakage even if application queries omit tenant filters.

### Key Architecture Components
1. **Database Enforced Isolation**: RLS policies on 13 tenant-scoped tables (`tenant_id = current_setting('app.current_tenant_id')::uuid`).
2. **Dynamic Context Injection**: Spring `OncePerRequestFilter` extracts tenant context per request and executes `SET LOCAL app.current_tenant_id` on the JDBC transaction connection.
3. **Integration Test Proof**: Automated integration tests executing against a real PostgreSQL instance via **Testcontainers**.

### Automated Test Proof Output

```text
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.taskforge.TenantIsolationIntegrationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 31.75 s -- in com.taskforge.TenantIsolationIntegrationTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

### Verified Test Scenarios ([TenantIsolationIntegrationTest.java](file:///backend/src/test/java/com/taskforge/TenantIsolationIntegrationTest.java))
- **Scenario 1**: Cross-Tenant Read Protection — Tenant B queries `findAll()` while Tenant A data exists → `0` rows returned.
- **Scenario 2**: Non-Matching Tenant Context — Query with invalid/missing UUID → Fail-safe empty result set returned.
- **Scenario 3**: Multi-Tenant Isolation — Concurrent records across 3 tenants → Each tenant strictly receives only its own rows.
- **Scenario 4**: Mid-Sequence Context Switch — Switching active context mid-transaction sequence maintains strict data boundaries.

---

## 📊 Phase 1 Quantitative Metrics Summary

| Metric Category | Verified Value | Evidence Location / Verification Method |
|---|---|---|
| **RLS Assertions** | **17** assertions | [`TenantIsolationIntegrationTest.java`](file:///backend/src/test/java/com/taskforge/TenantIsolationIntegrationTest.java) |
| **Isolation Scenarios** | **4** scenarios | `JUnit 5` test suite execution |
| **Database Migrations** | **7** migrations (`V1`–`V7`) | [`backend/src/main/resources/db/migration/`](file:///backend/src/main/resources/db/migration/) |
| **RLS-Protected Tables** | **13** tables | [`V7__enable_rls.sql`](file:///backend/src/main/resources/db/migration/V7__enable_rls.sql) |

Detailed measurement logs and metric tracking can be found in [`docs/METRICS_PLAYBOOK.md`](file:///docs/METRICS_PLAYBOOK.md).

---

## 🛠️ Tech Stack

- **Backend**: Java 21, Spring Boot 3.2.5, Spring Security, Spring Data JPA, Flyway
- **Database**: PostgreSQL 16 + `pgvector`
- **Testing**: JUnit 5, Testcontainers, AssertJ
- **Infrastructure**: Docker, Docker Compose
