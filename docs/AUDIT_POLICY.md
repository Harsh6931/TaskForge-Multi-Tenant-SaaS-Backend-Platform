# Audit Policy

_Owned by: [Your name] — Last updated: Phase 5_

This document defines what constitutes an auditable "critical action" versus noise in TaskForge.

## Guiding Principle

Audit logs exist for **security forensics and compliance**, not general observability. We log actions that, if performed maliciously or by mistake, would cause significant data loss, privilege escalation, or billing fraud. We do **not** log read-only operations — that belongs in application metrics/tracing (e.g., Prometheus + Jaeger), not an immutable audit trail.

## What We Log (Critical Actions)

| Category        | Action                                              | Justification                                              |
|-----------------|-----------------------------------------------------|------------------------------------------------------------|
| Auth            | User login (success + failure)                      | Detects brute-force and credential stuffing                |
| Auth            | Tenant switch                                       | Tracks privilege boundary crossings                        |
| Auth            | Refresh token revocation / logout                   | Session termination audit trail                            |
| Users           | Role change (promote / demote)                      | Privilege escalation is the #1 insider threat vector       |
| Users           | User invited to tenant                              | Tracks who expanded access                                 |
| Users           | User removed from tenant                            | Tracks who shrank access                                   |
| Projects        | Project created / deleted                           | Deletion is irreversible (soft-delete still matters)       |
| Tasks           | Task deleted                                        | Soft-delete; useful for "who deleted this?"                |
| Tasks           | Task status changed to DONE / reopened              | Key workflow milestone for billing/reporting               |
| API Keys        | API key created                                     | New credential issued — track who/when                     |
| API Keys        | API key revoked                                     | Credential invalidated — track who/when                    |
| Billing         | Subscription plan changed (upgrade / downgrade)     | Revenue-impacting event                                    |
| Billing         | Payment failed                                      | Compliance + customer success follow-up                    |
| Billing         | Webhook received from Stripe                        | Idempotency verification requires a record                 |

## What We Do NOT Log (Noise)

| Action                             | Reason                                                        |
|------------------------------------|---------------------------------------------------------------|
| `GET /projects` / `GET /tasks`     | Read-only, high volume — use metrics/tracing instead          |
| Comment created / updated          | Low-risk, high-frequency — not worth audit overhead           |
| Dashboard stats viewed             | Read-only analytics; no security implication                  |
| Task title / description edits     | Low risk; version history (via `version` column) suffices     |
| Notification marked as read        | Purely UI state, no security implication                      |
