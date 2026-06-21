# Rate Limits

_Owned by: [Your name] — Last updated: Phase 8_

This document defines the per-tenant rate limits per subscription plan. These values are enforced by the Redis token-bucket rate limiter in the backend.

## Thresholds

| Plan       | Requests / minute | Burst allowance |
|------------|:-----------------:|:---------------:|
| FREE       |        60         |       80        |
| PRO        |        300        |       400       |
| ENTERPRISE |      1 000        |     1 500       |

> **Retry-After header**: Returned with every `429 Too Many Requests` response, indicating seconds until the next token refill.

## Rationale

- FREE: Enough for individual use / evaluation without enabling scraping.
- PRO: Supports small-to-medium team automation (CI pipelines, integrations).
- ENTERPRISE: High enough for large-scale integrations; still protects infrastructure from runaway scripts.
