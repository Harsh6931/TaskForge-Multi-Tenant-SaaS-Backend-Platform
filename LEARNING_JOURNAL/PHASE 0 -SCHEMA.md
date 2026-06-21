# Phase 0 - Related to Schema

## Q1. Why does the tenants table need both name and slug?
name is human-readable and shown in the UI.

slug is URL-safe and used in routes.
EG.
Name:
Amazon Web Services Team Alpha

Slug:
aws-team-alpha

## Q. Why doesn't the `users` table contain `tenant_id`?

### Interview Answer

The `users` table represents global user identities, while tenant membership is managed through the `tenant_users` table. This allows a single user to belong to multiple tenants with different roles.

### Scenario Visualization

```text
Harshit
   │
   ├── Amazon Workspace (ADMIN)
   ├── Microsoft Workspace (VIEWER)
   └── OpenAI Workspace (MEMBER)
```

Database Design:

```text
users
   │
   ▼
tenant_users
   │
   ▼
tenants
```

### Failure Scenario

If `tenant_id` existed in the users table:

```sql
users(
    id,
    tenant_id,
    email
)
```

Then:

```text
Harshit
   ↓
Could belong to only ONE workspace
```

To join another workspace, a completely new account would be required.

### Real World Examples

* GitHub Organizations
* Slack Workspaces
* Notion Workspaces

One account can belong to many organizations.

### One-Line Interview Answer

Users represent global identities, while tenant membership and roles are managed through tenant_users.

---

## Q. Why do we store `password_hash` instead of the actual password?

### Interview Answer

Passwords should never be stored in plain text. Instead, a secure hash is stored so that database breaches do not immediately expose user credentials.

### Scenario Visualization

Bad Design:

```text
Email               Password
--------------------------------
john@gmail.com      john123
alice@gmail.com     alice123
```

Database Leak:

```text
Attacker
   ↓
Reads Database
   ↓
Gets All Passwords
```

Good Design:

```text
Email               Password Hash
--------------------------------
john@gmail.com      $2a$10$...
alice@gmail.com     $2a$10$...
```

Database Leak:

```text
Attacker
   ↓
Gets Hashes
   ↓
Must Crack Them First
```

### Failure Scenario

Without hashing:

```text
Database Leak
    ↓
All User Passwords Exposed
    ↓
Account Takeover
```

### Real World Analogy

```text
Password = House Key

Password Hash = Fingerprint Of The Key
```

The fingerprint helps verify the key but cannot directly open the door.

### One-Line Interview Answer

Password hashes protect user credentials even if the database is compromised.

---

## Q. Why is `email` marked as UNIQUE?

### Interview Answer

Email uniquely identifies a user account and prevents authentication ambiguity.

### Scenario Visualization

Without UNIQUE:

```text
User A
Email: harshit@gmail.com

User B
Email: harshit@gmail.com
```

Login Request:

```text
Email: harshit@gmail.com
Password: ****
```

System Question:

```text
Which account should be logged in?
```

Impossible to determine.

### Failure Scenario

Without uniqueness:

```text
Duplicate Accounts
    ↓
Broken Login
    ↓
Broken Password Reset
    ↓
Broken User Identity
```

### One-Line Interview Answer

Unique emails ensure every account has a single, unambiguous identity.

---

## Q. Why use `deleted_at` instead of permanently deleting users?

### Interview Answer

`deleted_at` implements soft delete, allowing data recovery while preserving historical records and referential integrity.

### Scenario Visualization

User:

```text
Harshit
```

Has:

```text
50 Projects
500 Comments
1000 Audit Logs
```

Hard Delete:

```sql
DELETE FROM users;
```

Result:

```text
Projects reference missing user
Comments reference missing user
Audit logs lose history
```

Soft Delete:

```sql
UPDATE users
SET deleted_at = NOW();
```

Result:

```text
User Hidden From UI
Data Still Exists
Can Be Restored
```

### Failure Scenario

Without soft deletes:

```text
Accidental Account Deletion
      ↓
Permanent Data Loss
      ↓
Broken Audit History
```

### Real World Analogy

```text
Hard Delete
= Burning A Book

Soft Delete
= Moving The Book To Storage
```

### One-Line Interview Answer

Soft deletes preserve historical data and allow recovery without breaking relationships across the system.

## Q. Why do we need a separate `tenant_users` table?

### Interview Answer

The `tenant_users` table models the membership relationship between users and tenants. It allows a single user to belong to multiple tenants and a tenant to contain multiple users.

### Scenario Visualization

```text
User:
Harshit

Tenants:
Amazon
Microsoft
OpenAI
```

Memberships:

```text
Harshit → Amazon
Harshit → Microsoft
Harshit → OpenAI
```

Database:

```text
users
   │
   ▼
tenant_users
   │
   ▼
tenants
```

### Failure Scenario

Without `tenant_users`:

```sql
users(
    id,
    tenant_id,
    email
)
```

A user could belong to only one tenant.

Result:

```text
Harshit
   ↓
Can Join Only Amazon
```

To join Microsoft, a second account would be required.

### Real World Examples

* GitHub Organizations
* Slack Workspaces
* Notion Workspaces

One account can belong to multiple organizations.

### One-Line Interview Answer

tenant_users acts as a membership table connecting users and tenants in a many-to-many relationship.

---

## Q. Why is the relationship between users and tenants many-to-many?

### Interview Answer

Because one tenant can have many users and one user can belong to many tenants.

### Scenario Visualization

```text
Amazon
├── Harshit
├── John
└── Alice

Microsoft
├── Harshit
└── Bob
```

Here:

```text
Harshit
```

belongs to:

```text
Amazon
Microsoft
```

And:

```text
Amazon
```

contains:

```text
Harshit
John
Alice
```

### Failure Scenario

Without a many-to-many design:

```text
User → One Tenant Only
```

or

```text
Tenant → One User Only
```

Neither matches real SaaS applications.

### One-Line Interview Answer

Users and tenants naturally form a many-to-many relationship, which is implemented using tenant_users.

---

## Q. Why is `role` stored in `tenant_users` instead of `users`?

### Interview Answer

Roles are tenant-specific, not user-specific.

### Scenario Visualization

Same User:

```text
Harshit
```

Different Tenants:

```text
Amazon     → ADMIN
Microsoft  → VIEWER
OpenAI     → MEMBER
```

If role were stored in users:

```sql
users(
    id,
    email,
    role
)
```

Harshit could have only one role everywhere.

### Failure Scenario

```text
ADMIN Everywhere
```

or

```text
VIEWER Everywhere
```

Neither reflects reality.

### Real World Example

GitHub:

```text
Personal Organization
    ↓
Owner

Company Organization
    ↓
Member
```

Same account.

Different permissions.

### One-Line Interview Answer

Roles are stored in tenant_users because permissions vary across tenants.

---

## Q. How does `tenant_users` enable RBAC?

### Interview Answer

RBAC (Role-Based Access Control) is implemented by assigning roles to users within a tenant through tenant_users.

### Scenario Visualization

```text
Amazon Workspace

Harshit → ADMIN
John    → MANAGER
Alice   → MEMBER
Bob     → VIEWER
```

Permissions:

```text
ADMIN
├── Manage Users
├── Manage Billing
└── Full Access

MANAGER
├── Manage Projects
└── Manage Tasks

MEMBER
└── Work On Tasks

VIEWER
└── Read Only
```

When a request arrives:

```text
User
   ↓
tenant_users
   ↓
Role Found
   ↓
Permission Checked
```

### Failure Scenario

Without tenant_users:

```text
No Tenant-Specific Roles
No Permission System
No RBAC
```

### One-Line Interview Answer

tenant_users serves as the foundation for RBAC by storing tenant-specific user roles.
## Q. Why does the `projects` table contain `tenant_id`?

### Interview Answer

tenant_id identifies which tenant owns a project and enables Row-Level Security (RLS) to enforce tenant isolation.

### Scenario Visualization

```text
Amazon
   ↓
Payment Gateway

Microsoft
   ↓
Azure Portal
```

Both projects exist in the same table.

RLS uses tenant_id to ensure Amazon cannot see Microsoft's projects.

### Failure Scenario

Without tenant_id:

```text
Database cannot determine project ownership.
RLS cannot work.
Cross-tenant isolation breaks.
```

### One-Line Interview Answer

tenant_id establishes project ownership and enables database-level tenant isolation.

---

## Q. Why store `created_by` as a foreign key to users?

### Interview Answer

created_by records who created the project while maintaining referential integrity.

### Scenario Visualization

```text
Harshit
   ↓
Creates
   ↓
Payment Gateway
```

Database:

```text
created_by = harshit_user_id
```

### Failure Scenario

If creator names were stored directly:

```text
created_by = "Harshit"
```

and the user later changes their name:

```text
Harshit Kumar Sinha
```

historical records become inconsistent.

### One-Line Interview Answer

created_by uses a foreign key to maintain accurate creator relationships and data consistency.

---

## Q. Why use `deleted_at` instead of permanently deleting projects?

### Interview Answer

deleted_at implements soft deletes, allowing project recovery while preserving related tasks, comments, and audit history.

### Scenario Visualization

```text
Project
   ↓
Contains
   ↓
100 Tasks
500 Comments
```

Accidental deletion:

Hard Delete:

```text
Everything Lost
```

Soft Delete:

```text
Project Hidden
Can Be Restored
```

### Failure Scenario

Permanent deletion can remove valuable project history and break related records.

### One-Line Interview Answer

Soft deletes preserve project history and allow recovery without losing related data.

## Q. Why does the `tasks` table contain `project_id`?

### Interview Answer

project_id associates a task with its parent project, allowing projects to contain multiple tasks.

### Scenario Visualization

```text
Project:
Payment Gateway

Tasks:
- Design API
- Build API
- Test API
```

Without project_id, tasks would have no relationship to projects.

### One-Line Interview Answer

project_id establishes the relationship between projects and tasks.

---

## Q. Why is `assignee_id` nullable?

### Interview Answer

Tasks may exist before a team member is assigned to them.

### Scenario Visualization

```text
Task Created
      ↓
Unassigned
      ↓
Later Assigned To Harshit
```

### Failure Scenario

If assignee_id were mandatory, every task would require an assignee at creation time.

### One-Line Interview Answer

Nullable assignee_id supports task creation before assignment.

---

## Q. Why use enums for `status`?

### Interview Answer

Enums restrict task status to valid workflow states and prevent invalid values.

### Scenario Visualization

```text
BACKLOG
   ↓
IN_PROGRESS
   ↓
DONE
```

### Failure Scenario

Without enums:

```text
started
working
almost_done
finished
completed
```

Inconsistent data appears.

### One-Line Interview Answer

Enums enforce valid workflow states and maintain data consistency.

---

## Q. Why use enums for `priority`?

### Interview Answer

Priority helps teams determine which tasks should be completed first.

### Scenario Visualization

```text
HIGH
↓
Production Outage

LOW
↓
Minor UI Improvement
```

### One-Line Interview Answer

Priority enables structured task prioritization and planning.

---

## Q. Why does the `tasks` table contain a `version` column?

### Interview Answer

The version column implements optimistic locking and prevents concurrent updates from overwriting each other.

### Scenario Visualization

```text
Version = 5

Harshit Opens Task
John Opens Task

Harshit Saves
Version → 6

John Saves
Expected = 5
Actual = 6

Update Rejected
```

### Failure Scenario

Without version:

```text
John's Save
      ↓
Overwrites Harshit's Changes
```

Result:

```text
Lost Updates
Data Corruption
```

### Real World Analogy

```text
Two people editing the same Google Doc simultaneously.
```

### One-Line Interview Answer

The version column prevents lost updates by detecting concurrent modifications through optimistic locking.
## Q. Why not allow multiple assignees? (in task table)
Single assignee provides clear ownership and simpler workflows. Multi-assignee support can be added later using a task_assignees junction table if collaborative ownership becomes a requirement.

## Q. Why does the `comments` table contain `tenant_id` when `task_id` already exists?

### Interview Answer

Although tenant ownership can be derived through task → project → tenant, storing tenant_id directly simplifies Row-Level Security policies and improves query performance.

### Scenario Visualization

```text
Comment
   ↓
Task
   ↓
Project
   ↓
Tenant
```

Without tenant_id, PostgreSQL would need additional joins to determine ownership.

With tenant_id:

```text
Comment
   ↓
Tenant
```

Ownership is immediately available.

### Failure Scenario

Without tenant_id:

```text
More Complex RLS Policies
More Joins
Higher Query Cost
```

### One-Line Interview Answer

tenant_id is denormalized into comments to simplify tenant isolation and improve performance.

---

## Q. Why does the `comments` table contain `user_id`?

### Interview Answer

user_id identifies the author of a comment while maintaining referential integrity.

### Scenario Visualization

```text
Task:
Fix Login Bug

Comment:
"Found the root cause."

Author:
Harshit
```

Database:

```text
user_id = harshit_user_id
```

### Failure Scenario

If author names were stored directly:

```text
author = "Harshit"
```

Name changes could make historical records inconsistent.

### One-Line Interview Answer

user_id links comments to their authors while preserving data consistency.

---

## Q. Why use soft deletes for comments?

### Interview Answer

Soft deletes preserve audit history and allow recovery while hiding deleted content from users.

### Scenario Visualization

```text
Comment Posted
      ↓
Comment Deleted
      ↓
Hidden From UI
      ↓
Still Exists For Audit
```

### Failure Scenario

Permanent deletion removes historical context and auditability.

### One-Line Interview Answer

Soft deletes maintain comment history while allowing content removal from normal views.
## Q. Why do we need a separate `task_labels` table?

### Interview Answer

A task can have multiple labels and a label can belong to multiple tasks. This creates a many-to-many relationship that is implemented using the task_labels junction table.

### Scenario Visualization

```text
Task:
Fix Login Bug

Labels:
Bug
Backend
Security
```

Database:

```text
Fix Login Bug → Bug
Fix Login Bug → Backend
Fix Login Bug → Security
```

Another task:

```text
Add Payment API → Backend
```

The same label is reused across multiple tasks.

### Failure Scenario

Without task_labels:

```text
One Task → One Label Only
```

or

```text
Store Labels As Comma-Separated Strings
```

Both are poor database designs.

### One-Line Interview Answer

task_labels implements the many-to-many relationship between tasks and labels.

---

## Q. Why does task_labels use a composite primary key?

### Interview Answer

The composite primary key prevents the same label from being assigned to the same task multiple times.

### Scenario Visualization

Allowed:

```text
Fix Login → Bug
Fix Login → Security
```

Rejected:

```text
Fix Login → Bug
Fix Login → Bug
```

Duplicate relationship.

### Failure Scenario

Without a composite primary key:

```text
Duplicate Labels
Data Inconsistency
```

### One-Line Interview Answer

The composite primary key guarantees uniqueness of task-label relationships.

---

## Q. Why does the labels table contain tenant_id?

### Interview Answer

Labels are tenant-specific and must be isolated between organizations.

### Scenario Visualization

```text
Amazon:
Bug
Security
Payments

Microsoft:
Azure
Backend
Frontend
```

Labels should not be shared across tenants.

### Failure Scenario

Without tenant_id:

```text
Cross-Tenant Label Leakage
```

### One-Line Interview Answer

tenant_id ensures labels remain isolated within each tenant.
## Q. Why does the plans table exist?

### Interview Answer

The plans table defines subscription tiers and usage limits for tenants.

### Scenario Visualization

```text
Free Plan
├── 5 Seats
├── 3 Projects
└── 1,000 API Calls

Pro Plan
├── 25 Seats
├── 50 Projects
└── 100,000 API Calls
```

When a tenant subscribes, their plan determines what resources they can use.

### Failure Scenario

Without plans:

```text
No Usage Limits
No Pricing Structure
No Subscription Model
```

### One-Line Interview Answer

The plans table centralizes pricing and usage limits for SaaS subscriptions.

---

## Q. Why store limits like max_seats and max_projects in the database?

### Interview Answer

Storing limits in the database allows plans to be modified without changing application code.

### Scenario Visualization

```text
Pro Plan

Old:
25 Seats

New:
50 Seats
```

Database update:

```text
No Code Changes Required
```

### Failure Scenario

If limits were hardcoded:

```text
Deploy Required
Code Changes Required
```

for every pricing adjustment.

### One-Line Interview Answer

Database-driven limits make pricing plans configurable and easier to maintain.

---

## Q. Why is price stored as price_cents instead of a decimal value?

### Interview Answer

Money should be stored in the smallest currency unit to avoid floating-point rounding errors.

### Scenario Visualization

Bad:

```text
9.99 + 9.99 + 9.99
```

Floating point may produce:

```text
29.970000000000002
```

Good:

```text
999 + 999 + 999
=
2997 cents
```

Exact calculation.

### Failure Scenario

Using floating-point values for money can produce inaccurate billing calculations.

### One-Line Interview Answer

price_cents avoids floating-point precision errors in financial calculations.

## Q. Why do we need a separate `subscriptions` table when tenants already have a `plan_id`?

### Interview Answer

Plans define available pricing tiers, while subscriptions represent a tenant's actual purchase and billing lifecycle.

### Scenario Visualization

Plan:

Pro
₹999/month

Tenant:

Amazon

Subscription:

Amazon → Pro

The subscription tracks billing-specific information such as status, renewal periods, and payment provider references.

### Failure Scenario

Without subscriptions:

- No billing history
- No renewal tracking
- No cancellation tracking
- No payment status management

### One-Line Interview Answer

Plans define offerings, while subscriptions track a tenant's active billing relationship.

---

## Q. Why does the subscription contain a `status` field?

### Interview Answer

Status tracks the billing state of a subscription and determines whether the tenant should have access to paid features.

### Scenario Visualization

ACTIVE
   ↓
Payment Fails
   ↓
PAST_DUE
   ↓
Still Unpaid
   ↓
CANCELLED

### Failure Scenario

Without status:

- Cannot distinguish active customers from unpaid customers
- Billing enforcement becomes impossible

### One-Line Interview Answer

Status allows the system to enforce billing rules and manage subscription lifecycles.

---

## Q. Why store `stripe_subscription_id`?

### Interview Answer

The Stripe subscription ID links the application's subscription record with the corresponding subscription in Stripe.

### Scenario Visualization

Stripe:

sub_XYZ123

TaskForge:

Subscription A

Database:

stripe_subscription_id = sub_XYZ123

When Stripe sends webhooks, the system can identify the correct subscription.

### Failure Scenario

Without Stripe IDs:

- Webhook processing becomes difficult
- Billing events cannot be matched reliably

### One-Line Interview Answer

stripe_subscription_id synchronizes internal subscriptions with Stripe's billing system.

---

## Q. Why store `current_period_start` and `current_period_end`?

### Interview Answer

These fields define the active billing cycle and are used for renewals, usage tracking, and subscription enforcement.

### Scenario Visualization

Current Period:

1 June → 30 June

Today:

25 June

Access:
Allowed

Today:

2 July

Subscription Expired

### Failure Scenario

Without billing periods:

- Cannot determine subscription validity
- Cannot enforce renewals

### One-Line Interview Answer

Billing periods define when a subscription is active and eligible for service access.

## Q. Why do we need refresh tokens?

### Interview Answer

Refresh tokens allow users to obtain new access tokens without logging in again after access tokens expire.

### Scenario Visualization

Login
   ↓
Access Token (15 min)
Refresh Token (30 days)

15 Minutes Later

Access Token Expired
      ↓
Use Refresh Token
      ↓
Get New Access Token

### Failure Scenario

Without refresh tokens:

- Users must log in repeatedly
- Poor user experience
- Frequent password entry

### One-Line Interview Answer

Refresh tokens provide long-lived authentication while keeping access tokens short-lived and secure.

---

## Q. Why store `token_hash` instead of the actual refresh token?

### Interview Answer

Refresh tokens are stored as hashes so database breaches do not immediately allow account takeover.

### Scenario Visualization

Bad:

Database Leak
      ↓
Attacker Gets Refresh Token
      ↓
Generates New Access Tokens

Good:

Database Leak
      ↓
Attacker Gets Token Hashes
      ↓
Cannot Directly Use Them

### Failure Scenario

Storing raw refresh tokens allows attackers to impersonate users if the database is compromised.

### One-Line Interview Answer

token_hash protects refresh tokens using the same security principle used for passwords.

---

## Q. Why does the table contain `revoked_at`?

### Interview Answer

revoked_at allows refresh tokens to be invalidated before their expiration date.

### Scenario Visualization

User Logout
      ↓
revoked_at = NOW()
      ↓
Token Becomes Invalid

### Another Scenario

Phone Stolen
      ↓
Logout From All Devices
      ↓
All Refresh Tokens Revoked

### Failure Scenario

Without revocation:

- Logout cannot truly invalidate sessions
- Stolen tokens remain usable

### One-Line Interview Answer

revoked_at enables secure logout and session invalidation.

---

## Q. Why do refresh tokens have an expiration date?

### Interview Answer

Expiration limits the lifetime of stolen or forgotten refresh tokens.

### Scenario Visualization

Created:
1 June

Expires:
1 July

Attempt Usage:
10 July

Result:
Rejected

### Failure Scenario

Without expiration:

- Tokens could remain valid forever
- Security risk increases significantly

### One-Line Interview Answer

Expiration reduces the security impact of compromised refresh tokens.

## Q. Why do we need an audit_logs table?

### Interview Answer

Audit logs provide a permanent record of important system actions, enabling traceability, security investigations, and compliance requirements.

### Scenario Visualization

Project Deleted
      ↓
Question:
Who deleted it?

Audit Log:
User = Harshit
Action = DELETE_PROJECT
Time = 22-Jun-2026

Answer Found.

### Failure Scenario

Without audit logs:

- Cannot investigate incidents
- Cannot determine who performed actions
- No compliance trail

### One-Line Interview Answer

Audit logs provide accountability and traceability for important system events.

---

## Q. Why store `action`, `resource_type`, and `resource_id` separately?

### Interview Answer

Together they describe exactly what happened and which resource was affected.

### Scenario Visualization

Action:
DELETE

Resource Type:
PROJECT

Resource Id:
123

Meaning:

Delete Project #123

### Failure Scenario

Without resource details:

- Events become ambiguous
- Difficult to investigate incidents

### One-Line Interview Answer

These fields create a complete description of each audited event.

---

## Q. Why use `metadata jsonb`?

### Interview Answer

Different actions require different contextual information, making JSON a flexible storage format.

### Scenario Visualization

Role Change:

{
  "oldRole":"MEMBER",
  "newRole":"ADMIN"
}

Project Creation:

{
  "projectName":"Payment Gateway"
}

Both fit into the same field.

### Failure Scenario

Without JSON:

- Large numbers of nullable columns
- Difficult schema evolution

### Real World Analogy

metadata is like attaching notes to an event explaining what happened.

### One-Line Interview Answer

metadata jsonb provides flexible storage for action-specific audit details.

---

## Q. Why is audit logging considered a production-grade feature?

### Interview Answer

Audit logs support compliance, debugging, incident investigation, and accountability requirements that are common in real-world SaaS systems.

### Scenario Visualization

100 Tasks Deleted
      ↓
Audit Logs Checked
      ↓
User Identified
      ↓
Root Cause Found

### One-Line Interview Answer

Audit logging enables accountability and operational visibility in production systems.

## Q. Why do we need API keys?

### Interview Answer

API keys allow non-human systems such as CI/CD pipelines, integrations, and backend services to authenticate with the platform.

### Scenario Visualization

GitHub Action
      ↓
Calls TaskForge API
      ↓
Uses API Key
      ↓
Authenticated

### Failure Scenario

Without API keys:

- External integrations cannot authenticate
- Automation becomes difficult
- Service-to-service communication is harder

### One-Line Interview Answer

API keys provide authentication for machines and integrations.

---

## Q. Why store `key_hash` instead of the actual API key?

### Interview Answer

API keys are stored as hashes so database breaches do not expose usable credentials.

### Scenario Visualization

Bad:

Database Leak
      ↓
Attacker Gets API Keys
      ↓
Calls APIs

Good:

Database Leak
      ↓
Attacker Gets Hashes
      ↓
Cannot Directly Use Them

### Failure Scenario

Raw API keys allow immediate unauthorized access after a database compromise.

### One-Line Interview Answer

key_hash protects API credentials using the same principle as password hashing.

---

## Q. Why does the table contain `last_used_at`?

### Interview Answer

last_used_at helps identify active, inactive, and potentially abandoned API keys.

### Scenario Visualization

Key A:
Last Used 5 Minutes Ago

Key B:
Last Used 9 Months Ago

Key B may no longer be needed.

### Failure Scenario

Without usage tracking:

- Difficult key management
- Harder security reviews

### One-Line Interview Answer

last_used_at improves visibility and lifecycle management of API credentials.

---

## Q. Why does the table contain `revoked_at`?

### Interview Answer

revoked_at allows API keys to be disabled immediately without deleting historical records.

### Scenario Visualization

API Key Leaked
      ↓
revoked_at = NOW()
      ↓
Key Stops Working

### Failure Scenario

Without revocation:

- Leaked keys remain usable
- Security incidents become harder to contain

### One-Line Interview Answer

revoked_at enables secure and immediate API key invalidation.

## Q. Why do we need a `usage_records` table?

### Interview Answer

The usage_records table tracks resource consumption for each tenant and enables quota enforcement, analytics, and usage-based billing.

### Scenario Visualization

Pro Plan

100,000 API Calls

Amazon Usage:

75,000 API Calls

System:

100,000 - 75,000

Remaining:
25,000

### Failure Scenario

Without usage tracking:

- Cannot enforce plan limits
- Cannot calculate usage-based billing
- No visibility into customer consumption

### One-Line Interview Answer

usage_records tracks tenant resource consumption for billing and quota enforcement.

---

## Q. Why use a `metric` enum?

### Interview Answer

The metric field identifies which resource is being measured, such as API calls or storage usage.

### Scenario Visualization

API_CALLS
↓
50,000

STORAGE_MB
↓
1,200

Same table.

Different resources.

### Failure Scenario

Without metric:

- Usage values become meaningless
- Multiple tracking tables required

### One-Line Interview Answer

metric allows a single table to track multiple resource types consistently.

---

## Q. Why store `period_start` and `period_end`?

### Interview Answer

Billing and quota enforcement are typically based on time periods, so usage must be associated with a billing cycle.

### Scenario Visualization

1 June
   ↓
Usage Accumulates
   ↓
30 June

Billing Calculated

New Period Begins

### Failure Scenario

Without billing periods:

- Cannot determine monthly usage
- Cannot reset quotas correctly

### One-Line Interview Answer

Billing periods allow usage tracking and quota enforcement on a recurring cycle.

## Q. Why do we need a notifications table?

### Interview Answer

The notifications table stores user-specific events and enables in-app notification delivery.

### Scenario Visualization

Task Assigned
      ↓
Create Notification
      ↓
Show In Notification Center

### Failure Scenario

Without notifications:

- Users miss important updates
- Poor collaboration experience
- No event visibility

### One-Line Interview Answer

Notifications keep users informed about important events in the system.

---

## Q. Why store a `type` instead of the full notification message?

### Interview Answer

The type identifies the event while allowing the frontend to generate messages dynamically.

### Scenario Visualization

type = TASK_ASSIGNED

Frontend:

"Task assigned to you"

type = COMMENT_ADDED

Frontend:

"New comment added"

### Failure Scenario

Storing full messages makes localization and UI customization difficult.

### One-Line Interview Answer

Notification types separate event meaning from presentation.

---

## Q. Why use `payload jsonb`?

### Interview Answer

Different notification types require different contextual data, making JSON a flexible storage format.

### Scenario Visualization

TASK_ASSIGNED:

{
  "taskId":"123"
}

COMMENT_ADDED:

{
  "commentId":"456"
}

### Failure Scenario

Without JSON:

- Many nullable columns
- Difficult schema evolution

### One-Line Interview Answer

payload jsonb provides flexible event-specific notification data.

---

## Q. Why use `read_at` instead of `is_read`?

### Interview Answer

read_at records both whether a notification was read and when it was read.

### Scenario Visualization

Unread:

read_at = NULL

Read:

read_at = 2026-06-22 10:30

### Failure Scenario

A boolean only tells whether the notification was read, not when.

### One-Line Interview Answer

read_at provides richer information than a simple boolean flag.
## Q. Why do we need a `task_embeddings` table?

### Interview Answer

The task_embeddings table enables semantic search by storing vector representations of tasks generated by an AI embedding model.

### Scenario Visualization

Task:

Fix Login Bug

↓

Embedding Model

↓

[0.12, -0.56, 0.91, ...]

Stored In Database

User Searches:

"Cannot sign in"

↓

Converted To Vector

↓

Similarity Search

↓

Returns:

- Fix Login Bug
- JWT Authentication Failure

### Failure Scenario

Without embeddings:

- Search only matches exact keywords
- Cannot understand semantic meaning
- Poor AI search experience

### One-Line Interview Answer

task_embeddings enables AI-powered semantic search using vector embeddings.

---

## Q. Why store embeddings separately from tasks?

### Interview Answer

Embeddings are large AI-specific data that most application queries do not require.

### Scenario Visualization

Most Requests:

Get Task
Update Task
List Tasks

Do NOT Need Embeddings

Only AI Search Needs Them.

### Failure Scenario

Storing embeddings directly in tasks increases storage and query overhead.

### One-Line Interview Answer

Separating embeddings keeps the main task table lightweight and efficient.

---

## Q. Why does task_embeddings contain tenant_id?

### Interview Answer

AI search must respect tenant isolation just like normal application data.

### Scenario Visualization

Amazon Tasks

↓

Search

↓

Only Amazon Embeddings

Microsoft Tasks

↓

Not Visible

### Failure Scenario

Without tenant_id:

AI search could leak tasks across tenants.

### One-Line Interview Answer

tenant_id ensures semantic search remains tenant-isolated.

---

## Q. What is an embedding?

### Interview Answer

An embedding is a numerical vector representation of text that captures semantic meaning.

### Scenario Visualization

"Fix Login Bug"

↓

Embedding Model

↓

[0.12, -0.56, 0.91, ...]

Similar meanings produce similar vectors.

Example:

- Fix Login Bug
- Cannot Sign In
- Authentication Failure

All become close together in vector space.

### Real World Analogy

Think of embeddings as GPS coordinates for meaning. Similar ideas are stored near each other.

### One-Line Interview Answer

Embeddings convert text into vectors that enable semantic similarity search.