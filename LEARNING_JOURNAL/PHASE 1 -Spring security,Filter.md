# Phase 1 Additional Notes — Spring Security, Filter Registration & Tenant Flow

## SecurityConfig Purpose

Spring Security dependency automatically protects all endpoints.

Without configuration:

```text
GET /projects
↓
401 Unauthorized
```

SecurityConfig temporarily allows all requests during Phase 1 so infrastructure (RLS, Flyway, tenant isolation) can be built before JWT authentication is added.

---

## @Spring Security Concepts

### SecurityFilterChain

Represents the sequence of security checks applied to every request.

```text
Request
   ↓
Security Filters
   ↓
Controller
```

Configured through:

```java
@Bean
SecurityFilterChain securityFilterChain(...)
```

---

## Why Disable CSRF?

CSRF mainly protects cookie-based session authentication.

TaskForge will use JWT:

```http
Authorization: Bearer <token>
```

Since authentication is not session-cookie based:

```java
.csrf(AbstractHttpConfigurer::disable)
```

is appropriate.

---

## Stateless Authentication

```java
.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
```

Meaning:

```text
No HTTP Sessions
No JSESSIONID
No server-side login state
```

Every request must carry authentication credentials.

Future:

```http
Authorization: Bearer JWT
```

---

## Why Disable Form Login?

Spring Security normally creates:

```text
/login
```

HTML login page automatically.

TaskForge uses:

```text
React Frontend
+
REST API
```

Authentication will happen through REST endpoints.

Therefore:

```java
.formLogin(...disable)
```

---

## Why Disable HTTP Basic?

HTTP Basic:

```http
Authorization: Basic base64(username:password)
```

TaskForge will use JWT authentication instead.

---

## permitAll()

Current Phase 1:

```java
auth.anyRequest().permitAll()
```

Meaning:

```text
All requests allowed.
No authentication.
No authorization.
```

Phase 2 will replace this with JWT authentication and RBAC.

---

# TenantConfig

Purpose:

Registers TenantFilter with Spring Boot.

Without registration:

```text
TenantFilter exists
↓
Never executes
```

---

## FilterRegistrationBean

Provides control over:

* Which filter to register
* URL patterns
* Execution order
* Filter name

---

## URL Pattern

```java
registration.addUrlPatterns("/*");
```

Meaning:

```text
Apply TenantFilter to every request.
```

Examples:

```text
/projects
/tasks
/auth/login
```

All pass through the filter.

---

## Filter Order

```java
registration.setOrder(...)
```

Determines execution sequence.

Lower number:

```text
Runs earlier
```

Example:

```text
Order 1  → TenantFilter
Order 10 → Security
Order 20 → Logging
```

Execution:

```text
TenantFilter
↓
Security
↓
Controller
```

---

## Phase 2 Filter Order Change

Current:

```text
Header
↓
TenantFilter
↓
TenantContextHolder
```

Future:

```text
JWT Filter
↓
Extract tenant claim
↓
SecurityContext
↓
TenantFilter
↓
TenantContextHolder
```

JWT authentication must run first.

---

# TenantFilter

Purpose:

Extract tenant ID from request and store it in ThreadLocal.

---

## Why OncePerRequestFilter?

Guarantees:

```text
One request
↓
One execution
```

even if request forwarding occurs internally.

---

## Tenant Header

Phase 1:

```http
X-Tenant-ID
```

Example:

```http
GET /projects
X-Tenant-ID: tenant-uuid
```

---

## Request Flow

```text
Request
↓
Read X-Tenant-ID
↓
Convert to UUID
↓
TenantContextHolder.setTenantId()
↓
Continue filter chain
```

---

## filterChain.doFilter()

Very important:

```java
filterChain.doFilter(request, response);
```

Means:

```text
Continue processing request.
```

Without it:

```text
Request stops here.
Controller never executes.
```

---

## Invalid UUID Handling

If:

```http
X-Tenant-ID: abc123
```

Then:

```java
UUID.fromString(...)
```

throws:

```java
IllegalArgumentException
```

Response:

```http
400 Bad Request
```

---

## Why finally Is Critical

Tomcat reuses threads.

Without:

```java
TenantContextHolder.clear();
```

Example:

```text
Thread 1 → Tenant A
Request ends

Thread 1 reused
for Tenant B
```

Tenant A may remain in ThreadLocal.

Potential data leak.

Always:

```java
finally {
    TenantContextHolder.clear();
}
```

---

# TenantConnectionInterceptor

Purpose:

Inject tenant ID into PostgreSQL session.

Reads:

```java
TenantContextHolder.getTenantId()
```

Runs:

```sql
SET LOCAL app.current_tenant_id = '<tenant-id>'
```

---

## Why Before Repository Queries?

RLS needs tenant information before SQL runs.

Correct sequence:

```text
SET LOCAL
↓
Repository Query
↓
RLS Evaluation
```

Not:

```text
Repository Query
↓
SET LOCAL
```

---

## Why SET LOCAL Instead Of SET?

```sql
SET LOCAL
```

Scope:

```text
Current transaction only.
```

Automatically cleared after commit/rollback.

---

```sql
SET
```

Scope:

```text
Entire connection.
```

Dangerous with connection pools because the same connection may later serve another tenant.

---

## Transaction Requirement

SET LOCAL must execute inside a transaction.

Correct flow:

```text
@Transactional
      ↓
applyTenantContext()
      ↓
SET LOCAL
      ↓
Repository Queries
```

---

## TransactionSynchronizationManager

Used only for logging/debugging.

Allows execution of code after transaction completion.

Example:

```java
afterCompletion(...)
```

runs when:

```text
Commit
or
Rollback
```

Occurs.

---

# Phase 2 Security Upgrade

Current:

```text
Client
↓
X-Tenant-ID Header
↓
TenantFilter
```

Problem:

```text
Header can be forged.
```

User could manually send another tenant's ID.

---

Future:

```text
JWT Token
↓
JWT Validation
↓
Tenant Claim
↓
TenantFilter
```

Benefits:

* Tenant ID comes from signed token.
* Client cannot modify tenant information.
* Stronger security model.

---

# Interview Nuggets

### Why ThreadLocal?

Store request-specific tenant information.

---

### Why clear()?

Prevent thread-pool leakage and tenant data contamination.

---

### Why SET LOCAL?

Automatically removed after transaction completion.

Safe with connection pools.

---

### Why JWT instead of X-Tenant-ID?

Headers can be forged.

Tenant claim inside a signed JWT cannot be modified by the client.

---

### Why OncePerRequestFilter?

Guarantees filter executes exactly once per request.

---

### Biggest Security Guarantee

Even if application code executes:

```sql
SELECT * FROM projects;
```

without a tenant filter, PostgreSQL RLS still enforces tenant isolation.
