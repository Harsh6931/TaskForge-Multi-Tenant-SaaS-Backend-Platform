I designed a multi-tenant schema using PostgreSQL Row-Level Security so tenant data isolation is enforced at the database layer, not just application code. Even if a buggy query omits tenant filtering, cross-tenant data leakage is prevented.


"TaskForge uses PostgreSQL Row-Level Security in a shared-schema multi-tenant architecture. Every tenant-scoped table contains a tenant_id, and after authentication Spring sets app.current_tenant_id on the database session. PostgreSQL RLS policies automatically filter rows by tenant_id, ensuring tenant isolation even if application code accidentally omits tenant filters."

# PostgreSQL Row-Level Security (RLS) — Interview Questions & Answers

## Q1. Why did you choose RLS instead of only filtering by tenant_id in application code?

### Answer

Filtering in application code relies on developers remembering to add:

```sql
WHERE tenant_id = ?
```

to every query.

If even one query is written incorrectly, data from other tenants can leak.

RLS moves tenant isolation into PostgreSQL itself.

Even if a developer accidentally writes:

```sql
SELECT * FROM projects;
```

PostgreSQL automatically applies the tenant policy and prevents unauthorized rows from being returned.

This follows the principle of **defense in depth** by enforcing security at the database layer rather than relying solely on application logic.

---

## Q2. What would happen if a developer accidentally removes the tenant filter from a repository method?

### Answer

Without RLS:

```java
projectRepository.findAll();
```

could expose every tenant's data.

With RLS enabled:

```sql
SELECT * FROM projects;
```

still returns only rows matching the current tenant because PostgreSQL applies the RLS policy automatically.

Therefore a coding mistake does not become a security incident.

---

## Q3. Why is RLS particularly valuable in a shared-schema architecture?

### Answer

In a shared-schema architecture all tenants store data inside the same tables.

Example:

```text
projects
--------------------------------
id    tenant_id    name
1     A            Project A
2     B            Project B
```

Since data physically coexists in the same table, accidental data leakage becomes a major risk.

RLS provides strong isolation while keeping operational costs lower than schema-per-tenant or database-per-tenant approaches.

---

## Q4. How does PostgreSQL know which tenant is making the request?

### Answer

The application sets a session variable before executing queries:

```sql
SET app.current_tenant_id =
'tenant-uuid';
```

The RLS policy then reads:

```sql
current_setting('app.current_tenant_id')
```

and compares it against the row's tenant_id.

Only matching rows are returned.

---

## Q5. Why use a session variable instead of passing tenant_id into every query?

### Answer

Passing tenant_id into every query creates duplication and increases the chance of human error.

Using a session variable:

```sql
SET app.current_tenant_id = ...
```

allows PostgreSQL to apply tenant filtering automatically across all queries.

This centralizes tenant isolation logic in one place.

---

## Q6. How does Spring Boot integrate with RLS?

### Answer

After JWT authentication succeeds:

1. Extract tenant_id from JWT.
2. Execute:

```sql
SET app.current_tenant_id = '<tenant-id>';
```

3. Run repository queries.
4. PostgreSQL enforces RLS policies automatically.

The repositories themselves remain unaware of tenant filtering.

---

## Q7. Why is RLS considered more secure than repository-level filtering?

### Answer

Repository-level filtering is application-layer security.

RLS is database-layer security.

Database-layer controls are generally stronger because:

* They protect against developer mistakes.
* They protect ad-hoc SQL queries.
* They protect future code changes.
* They enforce rules consistently across all access paths.

---

## Q8. Does RLS affect INSERT operations?

### Answer

Yes.

RLS can control:

* SELECT
* INSERT
* UPDATE
* DELETE

For INSERT operations PostgreSQL can validate that:

```sql
tenant_id =
current_setting('app.current_tenant_id')
```

before allowing the row to be created.

This prevents users from inserting data into another tenant's workspace.

---

## Q9. Does RLS replace authorization (RBAC)?

### Answer

No.

RLS answers:

> Which rows can this tenant access?

RBAC answers:

> What actions can this user perform?

Example:

* RLS prevents Tenant A from seeing Tenant B's tasks.
* RBAC prevents a VIEWER from deleting tasks.

Both are required.

---

## Q10. What interview story demonstrates that RLS is working correctly?

### Answer

Test scenario:

1. Tenant A creates Project X.
2. Tenant B creates Project Y.
3. Execute:

```sql
SELECT * FROM projects;
```

without a WHERE clause.
4. Set:

```sql
SET app.current_tenant_id='tenant-b';
```

Result:

```text
Project Y
```

only.

Project X is invisible.

This proves tenant isolation exists even when application code is intentionally broken.

---

## Q11. What are the trade-offs of RLS?

### Answer

Advantages:

* Strong security.
* Centralized isolation.
* Protects against developer mistakes.
* Ideal for shared-schema SaaS systems.

Disadvantages:

* More complex to understand initially.
* Requires careful session management.
* Debugging queries can be harder because PostgreSQL silently filters rows.

The security benefits generally outweigh the additional complexity.

---

## Q12. What is the biggest mistake teams make when implementing RLS?

### Answer

Forgetting to set the tenant context before executing queries.

Example:

```sql
SET app.current_tenant_id
```

is never executed.

Now PostgreSQL cannot determine the active tenant and requests may fail.

The application must guarantee tenant context is established before any tenant-scoped query runs.

---

# Complete TaskForge RLS Request Flow

```text
┌─────────────────────────┐
│ HTTP Request            │
│ GET /projects           │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ JWT Authentication      │
│ Validate Access Token   │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ Extract tenant_id       │
│ From JWT Claims         │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ OncePerRequestFilter    │
│ Executes Before API     │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ SET app.current_        │
│ tenant_id='tenantA'     │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ Repository Query        │
│ SELECT * FROM projects  │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ PostgreSQL RLS Policy   │
│ tenant_id =             │
│ current_setting(...)    │
└────────────┬────────────┘
             │
             ▼
┌─────────────────────────┐
│ Only Tenant Data        │
│ Returned                │
└─────────────────────────┘
```

## Q. Why not store tenant_id in every table including join tables?

### Answer

Adding tenant_id everywhere simplifies RLS policies but duplicates data.

Example:

task_labels

- task_id
- label_id
- tenant_id

The tenant can already be derived from the task, so storing it again introduces redundancy and potential consistency issues.

Trade-off:

- Simpler queries
- More duplicated data

vs

- More complex RLS
- Better normalization

---

## Q. Why use current_setting() inside the RLS policy?

### Answer

Policies must know which tenant is making the request.

PostgreSQL sessions do not automatically know the tenant.

current_setting('app.current_tenant_id') allows the application to pass tenant context to PostgreSQL without modifying every query.

---

## Q. Why is RLS implemented in PostgreSQL instead of Spring Security?

### Answer

Spring Security protects API endpoints.

RLS protects database rows.

Even if a bug bypasses application authorization, PostgreSQL still prevents cross-tenant access.

They solve different problems and complement each other.

---

## Q. What would happen if a connection from the pool reused another tenant's context?

### Answer

Tenant data leakage could occur.

Because connection pools reuse database connections, the application must ensure app.current_tenant_id is set correctly for every request before queries execute.

This is one of the most important implementation concerns when using RLS.

---

## Q. Why does TaskForge use both tenant_id columns and RLS?

### Answer

RLS still needs something to filter on.

tenant_id identifies ownership.

RLS enforces access rules using that ownership information.

Without tenant_id, PostgreSQL cannot determine which rows belong to which tenant.

---

## Q. Can RLS be bypassed using a native SQL query?

### Answer

No.

RLS operates inside PostgreSQL.

Whether data is accessed through:

- JPA
- Hibernate
- JDBC
- Native SQL

the policy is still applied.

This is one of the biggest advantages of database-level security.

---

## Q. What type of companies commonly use RLS?

### Answer

Multi-tenant SaaS companies.

Examples:

- Project management tools
- CRM platforms
- Billing systems
- Internal enterprise SaaS

Any system where multiple customers share infrastructure but require strict data isolation.

---

## Q. What would be the first thing to check if Tenant A can suddenly see Tenant B's data?

### Answer

Check whether:

1. RLS is enabled.
2. FORCE RLS is enabled.
3. app.current_tenant_id is being set correctly.
4. The policy exists on the affected table.

Most RLS issues are caused by incorrect tenant context rather than policy logic.

---

## Q. How would you test RLS in production-like environments?

### Answer

Create two tenants.

Insert data for both.

Run identical queries while switching app.current_tenant_id.

Verify that each tenant only receives its own rows.

This proves isolation independently of application logic.

---

## Q. When might database-per-tenant be preferred over RLS?

### Answer

For extremely large customers or strict regulatory requirements.

Examples:

- Banking
- Healthcare
- Government systems

These environments may require complete physical separation of customer data.

## One-Line Interview Summary

"TaskForge uses PostgreSQL Row-Level Security in a shared-schema multi-tenant architecture. Spring Boot extracts the tenant from the JWT, sets app.current_tenant_id for the database session, and PostgreSQL automatically filters rows using RLS policies, preventing cross-tenant data leaks even if application code contains a faulty query."
