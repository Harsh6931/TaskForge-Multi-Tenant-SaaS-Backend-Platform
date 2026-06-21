# RBAC Permission Matrix

_Owned by: [Your name] — Last updated: Phase 2_

This document defines which roles can perform which actions in TaskForge. It is the authoritative source for all `@PreAuthorize` annotations in the backend.

## Roles

| Role     | Scope                                    |
|----------|------------------------------------------|
| ADMIN    | Full control over the tenant workspace   |
| MANAGER  | Manage projects, tasks, and team members |
| MEMBER   | Create and manage their own tasks        |
| VIEWER   | Read-only access                         |

## Permission Matrix

| Action                          | ADMIN | MANAGER | MEMBER | VIEWER |
|---------------------------------|:-----:|:-------:|:------:|:------:|
| Create project                  |  ✅   |   ✅    |   ❌   |   ❌   |
| Update / delete project         |  ✅   |   ✅    |   ❌   |   ❌   |
| View projects                   |  ✅   |   ✅    |   ✅   |   ✅   |
| Create task                     |  ✅   |   ✅    |   ✅   |   ❌   |
| Update task (own)               |  ✅   |   ✅    |   ✅   |   ❌   |
| Update task (any)               |  ✅   |   ✅    |   ❌   |   ❌   |
| Delete task                     |  ✅   |   ✅    |   ❌   |   ❌   |
| Assign task to other user       |  ✅   |   ✅    |   ❌   |   ❌   |
| Add / edit / delete comment     |  ✅   |   ✅    |   ✅   |   ❌   |
| Invite user to tenant           |  ✅   |   ✅    |   ❌   |   ❌   |
| Change user role                |  ✅   |   ❌    |   ❌   |   ❌   |
| Remove user from tenant         |  ✅   |   ❌    |   ❌   |   ❌   |
| View audit logs                 |  ✅   |   ❌    |   ❌   |   ❌   |
| Manage billing / subscriptions  |  ✅   |   ❌    |   ❌   |   ❌   |
| Create / revoke API keys        |  ✅   |   ❌    |   ❌   |   ❌   |
| View API keys (masked)          |  ✅   |   ✅    |   ❌   |   ❌   |
| Manage labels                   |  ✅   |   ✅    |   ❌   |   ❌   |

> **Note:** ADMIN is the only role that can perform irreversible or billing-sensitive actions. This intentionally limits blast radius if a MANAGER account is compromised.
