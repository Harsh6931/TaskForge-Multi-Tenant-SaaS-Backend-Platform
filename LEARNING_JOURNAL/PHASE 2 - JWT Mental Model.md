# Phase 2 — Goal 1: JWT Mental Model

> **No code in this goal.** Understand before you write. Everything in Goals 2–7 builds on this.

---

## What is a JWT?

A JSON Web Token is a compact, self-contained string that carries **verifiable claims** about a user.
It has three parts separated by dots: `header.payload.signature`

```
eyJhbGciOiJIUzI1NiJ9   ← Header  (Base64 encoded)
.
eyJzdWIiOiJ1c2VyLXV1aWQiLCJ0ZW5hbnRJZCI6InRlbmFudC11dWlkIiwicm9sZSI6IkFETUlOIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDAwMDA5MDB9
                        ← Payload (Base64 encoded — NOT encrypted, anyone can decode it)
.
HMACSHA256(header + "." + payload, secretKey)
                        ← Signature (cryptographic proof — only we can create this)
```

### Part 1 — Header
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```
Tells the verifier which algorithm was used to sign the token. We use `HS256` (HMAC-SHA256) — a symmetric algorithm, meaning both signing and verification use the **same secret key**. The alternative is `RS256` (asymmetric — private key signs, public key verifies). For a single backend service, HS256 is simpler and sufficient.

### Part 2 — Payload (Claims)
```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",   ← userId (standard "subject" claim)
  "tenantId": "660f9511-f3ac-52e5-b827-557766551111",
  "role": "ADMIN",
  "iat": 1700000000,                               ← issued at (Unix timestamp)
  "exp": 1700000900                                ← expires at (iat + 15 min = iat + 900)
}
```

⚠️ **CRITICAL:** The payload is Base64-encoded, NOT encrypted. Anyone with the token can decode it and read these claims. **Never put passwords, credit card numbers, or any sensitive PII in a JWT.**

What IS safe to put: userId, tenantId, role — identifiers that are meaningless without access to your system.

### Part 3 — Signature
```
HMAC-SHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secretKey
)
```

This is the **trust anchor**. The server creates it with the secret key. When a request comes in:
1. Server re-computes the signature from the header + payload using its secret
2. Compares to the signature in the token
3. If they match → token is authentic and unmodified ✅
4. If they don't match → token was tampered with → reject 401 ❌

The signature means: **even though anyone can read the payload, no one can change it without invalidating the signature.**

---

## Stateless Auth — Why It Matters

Traditional session auth:
```
Client login → Server creates session → Server STORES session in memory/Redis
→ Client sends session cookie → Server looks up session in store
Problem: server must store session for every logged-in user. Can't horizontally scale easily.
```

JWT stateless auth:
```
Client login → Server issues JWT → Server stores NOTHING
→ Client sends JWT on every request → Server only validates the signature
Benefit: any server instance can validate any JWT (no shared state needed)
```

The server is **stateless with respect to auth** — the token carries its own proof of validity.

---

## Access Token vs Refresh Token

Why two tokens? Short answer: **security vs. usability trade-off.**

| | Access Token | Refresh Token |
|---|---|---|
| **Lifetime** | 15 minutes | 7 days |
| **Sent with** | Every API call (`Authorization: Bearer`) | Only to `/auth/refresh` |
| **Stored server-side** | ❌ No | ✅ Yes (as SHA-256 hash) |
| **Revocable server-side** | ❌ No (wait for expiry) | ✅ Yes (set `revoked_at`) |
| **If stolen** | Attacker can act for up to 15 min | Can be revoked immediately |

**The dance:**
```
Login → get access_token (15min) + refresh_token (7 days)
↓
Use access_token for API calls
↓
access_token expires (15 min later)
↓
Send refresh_token to /auth/refresh
→ Server validates refresh_token hash in DB
→ Server revokes old refresh_token, issues new refresh_token
→ Server issues new access_token
↓
Resume API calls with new access_token
```

---

## Refresh Token Rotation

Each call to `/auth/refresh`:
1. Old refresh token → mark `revoked_at = now()` in DB
2. New refresh token → store new hash in DB
3. New access token → issue fresh 15-min token

**Why rotation?** If a stolen refresh token is used by an attacker, the next time the **real user** tries to refresh, they'll get a 401 (their token was already rotated out by the attacker). This is a detectable signal that the token was compromised.

Without rotation: a stolen refresh token works for 7 full days silently.

---

## Token Signing Key Requirements

JJWT enforces that the secret key used for HS256 must be **at least 256 bits (32 bytes)**.

```
"change-me-in-production-use-a-long-random-string"
```
The dev placeholder in `application.yml` is loaded from `${JWT_SECRET}` env var in production.

**In production:** generate with:
```bash
openssl rand -base64 64
```
Store in your secrets manager (Railway env vars, AWS Secrets Manager, etc.) — never in source code.

---

## Why SHA-256 for Refresh Tokens (not BCrypt)?

Passwords → BCrypt (slow by design, work factor, adds salt) — protects against brute-force dictionary attacks.

Refresh tokens → SHA-256 (fast) — the token is a random UUID (128 bits of entropy). There's no dictionary to brute-force. Even if an attacker knows a hash, they'd need to find the preimage of a random 128-bit value — computationally infeasible.

BCrypt on a random token would waste CPU cycles for zero extra security.

---

## The JWT Flow in TaskForge

```
1. POST /auth/login
   ├─ Validate email + BCrypt password match
   ├─ Build access token: { sub: userId, tenantId, role, exp: now+15min }
   ├─ Sign with HMAC-SHA256(secretKey)
   ├─ Generate raw refresh token (UUID)
   ├─ Store SHA-256(raw refresh token) in refresh_tokens table
   └─ Return { accessToken, refreshToken, tenants[] }

2. Every API call
   ├─ JwtAuthenticationFilter extracts Bearer token
   ├─ JwtService.parseAccessToken() verifies signature + expiry
   ├─ Claims → userId, tenantId, role → SecurityContext
   └─ TenantContextHolder.setTenantId() → RLS activates

3. POST /auth/refresh
   ├─ Receive raw refresh token
   ├─ SHA-256(raw) → look up in DB
   ├─ Verify not revoked + not expired
   ├─ Revoke old token (set revoked_at)
   ├─ Issue new access + refresh token pair
   └─ Return new tokens

4. POST /auth/logout
   └─ Set revoked_at on ALL refresh tokens for userId (logout everywhere)
```

---

## Interview Talking Points (memorize these)

1. **"Why stateless?"** → Any instance validates any token without shared session state. Scales horizontally.

2. **"Why short access tokens?"** → If stolen, damage is time-limited to 15 minutes. We can't revoke JWTs server-side (we'd need a blocklist — defeats statelessness). Short expiry is the mitigation.

3. **"Why refresh token rotation?"** → A stolen refresh token can be detected: when the real user refreshes, theirs was already rotated — they get 401, which signals compromise.

4. **"Why hash the refresh token?"** → If the DB leaks, raw tokens aren't exposed. SHA-256 is sufficient here (random 128-bit tokens, no dictionary attacks possible).

5. **"How does the JWT carry tenant isolation?"** → The token embeds `tenantId`. Every request reads it → sets `TenantContextHolder` → `TenantConnectionInterceptor` runs `SET LOCAL app.current_tenant_id = ?` → PostgreSQL RLS filters all queries to that tenant automatically.
