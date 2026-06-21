# TaskForge Learning Journal

This document contains concepts, interview questions, visualizations, scenarios, engineering decisions, and lessons learned while building TaskForge.

# LEARNINGS.md

# Instructions for Maintaining This File

This document serves as:

* Personal Learning Journal
* Engineering Decision Log
* Interview Preparation Handbook
* Project Knowledge Base

Every new concept, feature, design decision, technology, or phase added to this project must follow the structure below.

---

## Required Structure For Every Concept

### 1. Interview Question

Start with realistic interview-style questions.

Examples:

* What is JWT?
* Why use Refresh Tokens?
* Why use PostgreSQL RLS?
* What is Cursor Pagination?
* What problem does Redis Rate Limiting solve?
* Why use WebSockets instead of Polling?
* Why use Optimistic Locking?

---

### 2. Interview Answer

Provide a concise answer suitable for an interview.

Goal:

* 30–60 second explanation
* Clear and direct
* No implementation details initially

---

### 3. Detailed Explanation

Explain:

* What it is
* Why it exists
* What problem it solves
* Advantages
* Disadvantages
* Alternatives

---

### 4. Scenario Visualization (MANDATORY)

Every concept must contain a realistic scenario.

The scenario should make the concept easy to visualize.

Example:

```text
Amazon
↓
Creates Project

Microsoft
↓
Queries Projects

RLS
↓
Blocks Access
```

Or:

```text
User Logs In
↓
Receives JWT
↓
Access Token Expires
↓
Refresh Token Used
↓
New Access Token Issued
```

The goal is to understand the concept through a story.

---

### 5. Failure Scenario (MANDATORY)

Always explain:

**What happens if we don't implement this?**

Examples:

Without RLS:

```text
Customer Data Leak
```

Without Refresh Tokens:

```text
Users Must Re-Login Frequently
```

Without Optimistic Locking:

```text
User Changes Overwrite Each Other
```

Without Rate Limiting:

```text
One Customer Can Exhaust System Resources
```

Failure scenarios often explain the importance of a concept better than definitions.

---

### 6. Real World Analogy

Whenever possible include an analogy.

Examples:

#### RLS

```text
Security Guard Checking Room Access
```

#### JWT

```text
Movie Ticket Proving You Already Paid
```

#### Refresh Token

```text
Long-Term Membership Card Used To Obtain New Tickets
```

#### Rate Limiting

```text
Water Flow Regulator Preventing One User From Taking All Water
```

#### Optimistic Locking

```text
Two People Editing The Same Google Doc
```

---

### 7. Interview Follow-Up Questions

Include likely follow-up questions.

Example:

#### For RLS

* Why not database-per-tenant?
* Why not schema-per-tenant?
* Why is RLS safer than WHERE tenant_id?
* What problem does RLS actually solve?

#### For JWT

* Why not sessions?
* Why store refresh tokens hashed?
* Why separate access and refresh tokens?

---

### 8. One-Line Interview Answer

End every concept with:

```md
### One-Line Interview Answer
```

Example:

```text
RLS enforces tenant isolation at the database layer, preventing cross-tenant data leaks even when application code contains bugs.
```

---

### 9. Implementation Notes

Document how the concept was implemented in TaskForge.

Example:

```text
Database:
PostgreSQL 16

RLS Policy:
tenant_id = current_setting('app.current_tenant_id')

Tenant Context:
Spring OncePerRequestFilter

Migration Tool:
Flyway
```

This connects theory with actual implementation.

---

## Primary Goal

Six months from now, after reading any section, I should be able to:

1. Understand the concept quickly.
2. Explain it in an interview.
3. Explain why we chose it.
4. Explain alternatives.
5. Explain what problem it solves.
6. Explain how it was implemented in TaskForge.
7. Explain the failure scenario if it was not implemented.

If a section cannot satisfy all seven goals, it is incomplete.

---

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

---

# Phase 2 — Authentication & Authorization

(To Be Added)

---

# Phase 3 — Projects & Tasks

(To Be Added)

---

# Phase 4 — Billing & Subscriptions

(To Be Added)

---

# Phase 5 — Audit Logging

(To Be Added)

---

# Phase 6 — AI Layer

(To Be Added)

---

# Phase 7 — Real-Time Collaboration

(To Be Added)

---

# Phase 8 — Observability, Caching & Performance

(To Be Added)

---

# Phase 9 — Deployment

(To Be Added)

---

# Phase 10 — Documentation & Resume Packaging

(To Be Added)

