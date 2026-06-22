
# Phase 1 — Tenant Isolation & Core Schema

---

## Q1. What is Multi-Tenancy?

### Interview Answer

Multi-tenancy is an architecture where a single application serves multiple customers while keeping their data isolated.

### Scenario Visualization

```text
Amazon
Microsoft
OpenAI
```

All use:

```text
One Application
One Database
```

But:

```text
Amazon cannot see Microsoft data
Microsoft cannot see OpenAI data
OpenAI cannot see Amazon data
```

### Real World Examples

* Slack Workspaces
* Notion Workspaces
* GitHub Organizations
* Jira Projects

---

## Q2. What is a Tenant?

### Interview Answer

A tenant represents a customer organization or workspace.

### Visualization

```text
TaskForge
│
├── Amazon
│   ├── Projects
│   ├── Tasks
│   └── Users
│
├── Microsoft
│   ├── Projects
│   ├── Tasks
│   └── Users
│
└── OpenAI
    ├── Projects
    ├── Tasks
    └── Users
```

Think of a tenant as a company account.

---

## Q3. Which Multi-Tenant Strategy Did We Choose?

### Answer

Shared Database + Shared Schema + PostgreSQL Row-Level Security (RLS)

### Visualization

```text
PostgreSQL
│
├── tenants
├── projects
├── tasks
├── comments
└── users
```

One database.

One set of tables.

All customers share them.

---

## Q4. Why Not Database Per Tenant?

### Visualization

```text
Amazon DB
Microsoft DB
OpenAI DB
Netflix DB
Spotify DB
...
```

For:

```text
10000 Customers
```

You now manage:

```text
10000 Databases
```

### Problems

* Expensive
* Hard backups
* Hard migrations
* Hard monitoring

---

## Q5. Why Not Schema Per Tenant?

### Visualization

```text
taskforge_db

amazon.projects
amazon.tasks

microsoft.projects
microsoft.tasks

openai.projects
openai.tasks
```

### Problem

Every migration must be executed on every schema.

Operational complexity grows quickly.

---

## Q6. Why Does Every Table Have tenant_id?

### Answer

tenant_id identifies ownership of data.

### Example

| Project         | Tenant    |
| --------------- | --------- |
| Payment Gateway | Amazon    |
| Azure Portal    | Microsoft |
| GPT Training    | OpenAI    |

Without tenant_id:

```text
Who owns this row?
```

No one knows.

---

## Q7. What Is Row-Level Security (RLS)?

### Interview Answer

RLS is a PostgreSQL feature that automatically filters rows before returning them to the application.

It enforces security at the database layer.

### Simple Visualization

Without RLS:

```sql
SELECT * FROM projects;
```

Returns:

```text
Amazon Projects
Microsoft Projects
OpenAI Projects
```

Data Leak.

---

With RLS:

```sql
SELECT * FROM projects;
```

Amazon User Sees:

```text
Amazon Projects Only
```

---

## Q8. What Problem Does RLS Actually Solve?

### Interview Answer

RLS prevents cross-tenant data leaks caused by developer mistakes.

Security is enforced by PostgreSQL instead of relying solely on application code.

---

### Detailed Scenario

Suppose TaskForge has:

```text
Amazon
Microsoft
OpenAI
```

Projects Table:

| id | tenant_id | project_name     |
| -- | --------- | ---------------- |
| 1  | Amazon    | Payment Gateway  |
| 2  | Amazon    | Inventory System |
| 3  | Microsoft | Azure Portal     |
| 4  | OpenAI    | GPT Training     |
| 5  | OpenAI    | AI Research      |

---

### Without RLS

Developer writes:

```sql
SELECT * FROM projects;
```

Expected:

```text
Amazon User
↓
Should only see Amazon Projects
```

Actual:

```text
Payment Gateway
Inventory System
Azure Portal
GPT Training
AI Research
```

### Problem

Amazon can now see:

* Microsoft's projects
* OpenAI's projects

Customer data leak.

---

### Traditional Fix

Developer writes:

```sql
SELECT *
FROM projects
WHERE tenant_id='Amazon';
```

Works.

---

### Real Problem

Six months later:

```sql
SELECT * FROM projects;
```

Developer forgets:

```sql
WHERE tenant_id='Amazon';
```

Leak happens again.

---

### With RLS Enabled

When Amazon logs in:

```sql
SET app.current_tenant_id='Amazon';
```

PostgreSQL remembers:

```text
Current Tenant = Amazon
```

Policy:

```sql
tenant_id =
current_setting('app.current_tenant_id')
```

Now:

```sql
SELECT * FROM projects;
```

Behaves like:

```sql
SELECT *
FROM projects
WHERE tenant_id='Amazon';
```

Automatically.

---

### Actual Result

Amazon User Sees:

```text
Payment Gateway
Inventory System
```

Microsoft and OpenAI rows never leave the database.

---

### Why Interviewers Like RLS

Without RLS:

```text
Security depends on developers.
```

With RLS:

```text
Security depends on the database.
```

---

### One-Line Interview Answer

RLS enforces tenant isolation at the database layer, ensuring customer data remains protected even when application code contains bugs or missing tenant filters.

---

## Q9. Why Is RLS Better Than WHERE tenant_id?

### Answer

Application-level filtering can fail.

Database-level filtering cannot be accidentally forgotten.

### One-Liner

RLS protects against developer mistakes.

---

## Q10. What Is current_setting('app.current_tenant_id')?

### Think Of It As

```text
Current Logged-In Company
```

Example:

```sql
SET app.current_tenant_id='amazon-id';
```

Database now knows:

```text
Current Tenant = Amazon
```

---

## Q11. How Does PostgreSQL Know The Current Tenant?

### Full Request Flow

```text
User Request
      │
      ▼
JWT Token
      │
      ▼
Extract tenant_id
      │
      ▼
OncePerRequestFilter
      │
      ▼
SET app.current_tenant_id
      │
      ▼
Repository Query
      │
      ▼
RLS Applied
      │
      ▼
Safe Data Returned
```

---

## Q12. What Is OncePerRequestFilter?

### Interview Answer

A Spring Security filter executed exactly once for every request.

### What We Use It For

```text
Read JWT
      ↓
Extract tenant_id
      ↓
Set PostgreSQL Session Variable
      ↓
Continue Request
```

---

## Q13. What Is Flyway?

### Think Of It As

```text
Git For Database Schema
```

Instead of manually creating tables.

We create:

```text
V1__initial_schema.sql

V2__tenant_rls.sql

V3__indexes.sql
```

Flyway runs them automatically.

---

## Q14. Why Use Flyway?

### Benefits

* Version controlled schema
* Reproducible deployments
* Easy team collaboration
* Consistent environments

---
# Flyway Database Migrations

---

## Interview Question

### What is Flyway?

### Why use Flyway instead of manually running SQL scripts?

### Why is Flyway often called "Git for Databases"?

### What is `flyway_schema_history`?

### What happens if an already executed migration is modified?

### How does Flyway handle multiple developers creating migrations at the same time?

---

# Interview Answer

Flyway is a database migration tool that manages schema changes through version-controlled SQL files. It ensures every environment applies database changes in the same order and keeps track of executed migrations using the `flyway_schema_history` table.

I think of Flyway as Git for the database. Instead of code commits, we create migration files that represent permanent database history.

Once a migration has been executed, it should never be modified. Any future changes must be introduced through a new migration.

---

# Core Problem Flyway Solves

Imagine TaskForge has:

```text
Developer A
Developer B
Staging
Production
```

All four databases must have exactly the same schema.

Without Flyway:

```text
Developer A
↓
Runs SQL manually

Developer B
↓
Forgets one SQL command

Production
↓
Missing column

Result
↓
Application crashes
```

The problem is not writing SQL.

The problem is keeping every database synchronized forever.

That is what Flyway solves.

---

# Mental Model

## Flyway = Git For Databases

Git tracks:

```text
Commit 1
↓
Commit 2
↓
Commit 3
```

Flyway tracks:

```text
Migration 1
↓
Migration 2
↓
Migration 3
```

Git remembers:

```text
Code History
```

Flyway remembers:

```text
Database History
```

Git commits should not be rewritten after they become shared history.

Similarly:

```text
Executed Flyway Migrations
```

should never be edited.

---

# How Flyway Works

Migration Folder:

```text
db/migration

V1__create_tenants.sql
V2__create_users.sql
V3__create_projects.sql
```

Application Startup:

```text
Spring Boot Starts
        ↓
Connects To PostgreSQL
        ↓
Flyway Starts
        ↓
Checks Migration History
        ↓
Runs Missing Migrations
        ↓
JPA/Hibernate Starts
        ↓
Application Ready
```

Important:

Flyway runs BEFORE JPA.

Why?

Because JPA expects tables to already exist.

Without migrations:

```text
JPA Starts
↓
Table Missing
↓
Application Failure
```

---

# Migration Naming Convention

Format:

```text
V{version}__{description}.sql
```

Examples:

```text
V1__create_tenants.sql
V2__create_users.sql
V3__create_projects.sql
```

Rules:

```text
Version Must Be Unique
Double Underscore Required
Version Order Matters
```

---

# What Is flyway_schema_history?

Flyway automatically creates:

```sql
flyway_schema_history
```

Think of it as Flyway's notebook.

Example:

| Version | Description     |
| ------- | --------------- |
| 1       | create_tenants  |
| 2       | create_users    |
| 3       | create_projects |

Flyway checks this table during every startup.

If version already exists:

```text
Skip
```

If version missing:

```text
Execute Migration
```

---

# Scenario Visualization

## First Project Startup

Database:

```text
Empty
```

Migration Folder:

```text
V1
V2
V3
```

Startup:

```text
Flyway
↓
Run V1
↓
Run V2
↓
Run V3
```

History Table:

```text
V1
V2
V3
```

Database is ready.

---

## Future Startup

History Table:

```text
V1
V2
V3
```

Migration Folder:

```text
V1
V2
V3
V4
```

Startup:

```text
Flyway
↓
V1 Already Applied
↓
V2 Already Applied
↓
V3 Already Applied
↓
Run V4
```

Only V4 executes.

---

# Checksums And Immutable History

When Flyway executes a migration:

```text
V1__create_tenants.sql
```

it stores:

```text
Version
Description
Checksum
```

Checksum = file fingerprint.

Example:

```text
Original File
↓
Checksum ABC123
```

Later:

```text
Developer Edits V1
↓
Checksum XYZ999
```

Flyway detects:

```text
History Changed
```

and startup fails.

---

# Why Old Migrations Must Never Be Edited

Bad:

```text
V1 Applied
↓
Need New Column
↓
Edit V1
```

Result:

```text
Checksum Mismatch
Application Fails
```

Good:

```text
V1 Applied
↓
Need New Column
↓
Create V2
```

Example:

```sql
ALTER TABLE tenants
ADD COLUMN slug VARCHAR(255);
```

stored inside:

```text
V2__add_slug.sql
```

History remains intact.

---

# Multi-Developer Scenario

Current State:

```text
V7 Applied
```

Developer A creates:

```text
V8__add_due_date.sql
```

Developer B creates:

```text
V8__add_priority.sql
```

Repository Now Contains:

```text
V8__add_due_date.sql
V8__add_priority.sql
```

Flyway Startup:

```text
Duplicate Version Detected
↓
Validation Failure
```

Application refuses to start.

---

# How Teams Resolve This

Developer B rebases.

Sees:

```text
V8 Already Exists
```

Renames:

```text
V9__add_priority.sql
```

Final Order:

```text
V8
↓
V9
```

Everything works.

---

# Failure Scenario

## Without Flyway

```text
Developer A
↓
Adds Column

Developer B
↓
Never Runs SQL

Production
↓
Old Schema

Application
↓
Unexpected Errors
```

Every environment slowly becomes different.

---

## Without Checksums

```text
Migration Executed
↓
Developer Modifies History
↓
Database State Becomes Unclear
↓
Deployment Risks Increase
```

Nobody knows which schema version is correct.

---

# Real World Analogy

Imagine a building under construction.

Every change must be recorded in the official blueprint.

Bad:

```text
Erase Old Blueprint
Draw New One
```

Nobody knows what happened.

Good:

```text
Blueprint Version 1
↓
Blueprint Version 2
↓
Blueprint Version 3
```

Every change is documented.

Flyway migrations are those blueprint revisions.

---

# Interview Follow-Up Questions

### Why not manually run SQL?

Human error causes databases to drift apart.

---

### Why is Flyway called Git for Databases?

Because migrations create a permanent version-controlled history of schema changes.

---

### What does flyway_schema_history do?

Tracks executed migrations and their checksums.

---

### Why does Flyway run before JPA?

Because tables must exist before entities can be mapped.

---

### What happens if V1 is modified?

Checksum validation fails and application startup stops.

---

### What happens if two developers create V8?

Flyway detects duplicate versions and refuses to run until versions are resolved.

---

### Why create a new migration instead of editing an old one?

Database history must remain immutable.

---

# One-Line Interview Answer

Flyway is a database migration tool that version-controls schema changes, tracks migration history, and guarantees every environment evolves through the same sequence of database changes.

---

# Implementation Notes (TaskForge)

Purpose:

```text
Manage all database schema changes as code.
```

Migration Folder:

```text
backend/src/main/resources/db/migration
```

Configuration:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

History Table:

```text
flyway_schema_history
```

Migration Format:

```text
V{version}__{description}.sql
```

Example:

```text
V1__create_tenants.sql
V2__create_users.sql
V3__create_projects.sql
```

Golden Rule:

```text
Flyway is Git for the database.

Once a migration becomes history,
never modify it.
Create a new migration instead.
```


## Q15. What Is Soft Delete?

### Hard Delete

```sql
DELETE FROM projects;
```

Gone forever.

---

### Soft Delete

```sql
UPDATE projects
SET deleted_at = NOW();
```

Still exists.

Just hidden.

---

## Q16. Why Use Soft Delete?

### Scenario

Customer says:

```text
I accidentally deleted my project.
```

Hard Delete:

```text
Gone Forever
```

Soft Delete:

```text
Can Recover
```

---
## Q: Why use ddl-auto=validate instead of update?
In production systems, schema changes should be controlled and versioned through migration tools like Flyway. Using ddl-auto=update allows Hibernate to modify the database automatically, which can create unpredictable schema changes. By setting ddl-auto=validate, Hibernate only verifies that the schema matches the entities, while Flyway remains the single source of truth for database evolution. This makes deployments safer and more reproducible.


## Q17. Most Important Phase 1 Test

### Scenario

Tenant A:

```text
Creates Project:
Payment Gateway
```

Tenant B:

Developer accidentally writes:

```sql
SELECT * FROM projects;
```

Expected Result:

```text
Tenant B sees:
0 Amazon Projects
```

Because RLS blocks access.

---

## Q18. What Problem Does Phase 1 Solve?

### Without Phase 1

```text
One Bug
      ↓
Customer Data Leak
```

### With Phase 1

```text
One Bug
      ↓
RLS Blocks Leak
      ↓
Customer Data Safe
```

---

# Phase 1 Interview Summary

TaskForge uses a shared database and shared schema multi-tenant architecture. Every tenant-scoped table contains a tenant_id column. PostgreSQL Row-Level Security enforces tenant isolation using the current tenant stored in a PostgreSQL session variable. This prevents cross-tenant data leakage even when application code accidentally omits tenant filtering.