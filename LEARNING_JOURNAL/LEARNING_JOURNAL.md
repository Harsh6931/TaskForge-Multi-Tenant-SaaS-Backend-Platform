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

