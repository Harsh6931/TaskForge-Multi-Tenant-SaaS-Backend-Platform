# Phase 2 — Authentication & Authorization: Metrics & Resume Proof

> Collected: 2026-07-24 | Project: TaskForge Multi-Tenant SaaS Backend
> Stack: Java 21 · Spring Boot 3.2 · Spring Security 6 · JJWT 0.12.5 · BCrypt · PostgreSQL 16 RLS

---

## 📊 Raw Metric Data (Exact Numbers)

| Metric | Value | How Measured |
|--------|-------|--------------|
| **Phase 2 unit tests written** | **45** | `grep -rc "@Test" src/test/java/com/taskforge/` |
| **Phase 2 unit tests passing** | **45 / 45 (100%)** | `mvn test` — BUILD SUCCESS |
| **Auth REST endpoints shipped** | **8** | Counted from `@PostMapping` / `@GetMapping` / `@DeleteMapping` |
| **@PreAuthorize RBAC annotations** | **7** | `grep -rc "@PreAuthorize"` across all controllers |
| **Auth Java source files** | **21** | `Get-ChildItem src/main/java/com/taskforge/auth` |
| **Total backend Java source files** | **45** | `Get-ChildItem src/main/java/com/taskforge -Recurse` |
| **Flyway migration files** | **7** (V1–V7) | `src/main/resources/db/migration/` |
| **Total migration SQL lines** | **257** | `Get-Content *.sql | Measure-Object -Line` |
| **Security filters added** | **2** | `JwtAuthenticationFilter` + `ApiKeyAuthenticationFilter` |
| **Token hash algorithm** | **SHA-256** | `TokenHashUtil.java` — `MessageDigest("SHA-256")` |
| **JWT access token expiry** | **15 minutes** | `app.jwt.access-token-expiry-ms=900000` |
| **Refresh token expiry** | **7 days** | `app.jwt.refresh-token-expiry-ms=604800000` |
| **BCrypt rounds** | **10 rounds (2¹⁰ = 1,024 iterations)** | `new BCryptPasswordEncoder()` default |
| **API key format** | **`tf_<32-hex-chars>` (128-bit entropy)** | `ApiKeyService.generateRawKey()` |

---

## 🧪 Test Suite Breakdown (45 Tests, 4 Suites)

| Test Class | Tests | What It Covers |
|------------|-------|---------------|
| `JwtServiceTest` | **18** | Token generation, claim extraction (userId/tenantId/role), signature tampering rejection, expiry detection, uniqueness (1,000-sample), wrong-key rejection |
| `AuthServiceTest` | **13** | Signup (happy + duplicate email + BCrypt encoding), Login (happy + unknown email + wrong password), Refresh rotation, Token revocation, Logout-everywhere, Tenant switch (happy + 403) |
| `AuthControllerTest` | **10** | HTTP status codes (201/200/204/400/401/403/409), Bean Validation error maps, JSON field serialization for all 5 endpoints |
| `TokenHashUtilTest` | **4** | SHA-256 format (64-char hex), determinism, collision resistance, NIST test vector (`"hello"`) |

---

## 🔐 Security Architecture Summary

### Filter Chain Order (Phase 2)
```
Incoming Request
    │
    ▼ Servlet chain
[TenantFilter]          ← Phase 1 (X-Tenant-ID header fallback for dev/testing)
    │
    ▼ Spring Security FilterChainProxy
[ApiKeyAuthFilter]      ← Authorization: ApiKey tf_xxx  → sets SecurityContext + TenantContext
[JwtAuthFilter]         ← Authorization: Bearer <jwt>  → sets SecurityContext + TenantContext
[AuthorizationFilter]   ← enforces .anyRequest().authenticated() rules
    │
    ▼ Controller
[TenantConnectionInterceptor] ← SET LOCAL app.current_tenant_id = '<uuid>' (RLS trigger)
    │
    ▼ PostgreSQL RLS
Rows filtered by tenant_id = current_setting('app.current_tenant_id')
```

### Public vs Protected Endpoints
| Route | Auth Required | RBAC Role |
|-------|:------------:|:---------:|
| `POST /auth/signup` | ❌ Public | — |
| `POST /auth/login` | ❌ Public | — |
| `POST /auth/refresh` | ❌ Public | — |
| `GET /actuator/health` | ❌ Public | — |
| `POST /auth/logout` | ✅ JWT | Any authenticated |
| `POST /auth/switch-tenant/{id}` | ✅ JWT | Any authenticated |
| `POST /api-keys` | ✅ JWT | ADMIN only |
| `GET /api-keys` | ✅ JWT | ADMIN, MANAGER |
| `DELETE /api-keys/{id}` | ✅ JWT | ADMIN only |

---

## 📝 Resume Bullets (Copy-Paste Ready)

> Use these directly in the **Projects** section. Replace `[X]` with exact numbers confirmed above.

### Tier 1 — Lead Bullets (Most Impressive)

```
• Architected stateless JWT authentication for a multi-tenant SaaS platform using
  Spring Security 6, implementing dual-auth (Bearer token + API key) with a 2-filter
  security pipeline; 100% of 45 unit tests pass across 4 test suites (JwtServiceTest,
  AuthServiceTest, AuthControllerTest, TokenHashUtilTest)

• Implemented refresh token rotation with SHA-256 hashing (zero plaintext storage)
  and logout-everywhere bulk revocation via JPQL — tokens expire in 7 days server-side
  while 15-minute JWT access tokens stay stateless

• Designed RBAC (Admin/Manager/Member/Viewer) using @PreAuthorize annotations
  across 7 secured endpoints; role matrix documented in RBAC.md and enforced via
  Spring's @EnableMethodSecurity without coupling roles to URL patterns
```

### Tier 2 — Supporting Bullets

```
• Built API key authentication for B2B integrations: generates tf_<32-hex> keys
  (128-bit entropy), stores only SHA-256 hashes, returns raw key exactly once —
  identical security model to GitHub Personal Access Tokens

• Integrated JWT authentication with PostgreSQL Row-Level Security: JwtAuthenticationFilter
  extracts tenantId from claims and sets TenantContextHolder, which downstream interceptor
  uses to run SET LOCAL app.current_tenant_id before every DB transaction — tenant
  isolation enforced at both application layer and database layer

• Applied BCrypt (10 rounds, 2¹⁰ iterations) for password hashing; structured
  Spring Security FilterChain with STATELESS session policy, disabled CSRF/Form Login/
  HTTP Basic — all security-relevant design decisions documented in SecurityConfig.java
```

### Tier 3 — Quantified Impact Bullets (for Metrics Section)

```
• 45 unit + slice tests written for authentication layer (0 failures, 0 skipped)
• 8 REST endpoints shipped across 2 controllers (/auth/*, /api-keys)
• 21 Java source files added in auth package (entities, services, filters, DTOs, utils)
• 7 RBAC @PreAuthorize rules enforced across admin-only and role-gated routes
• 7 Flyway migration files (V1–V7, 257 SQL lines) covering full SCHEMA.md contract
```

---

## 🎤 Interview Talking Points

These are the questions you WILL be asked. Answers below are yours to rehearse.

### Q: "Walk me through your authentication flow."

> **A:** "When a user logs in, we validate BCrypt-hashed credentials, issue a 15-minute JWT access token and a 7-day refresh token. The refresh token is stored as a SHA-256 hash in the DB — never plaintext. Each refresh call rotates the token (old one is revoked immediately), preventing replay attacks. For logout-everywhere, we bulk-revoke all active refresh tokens for that userId via a single JPQL UPDATE. The JWT carries userId, tenantId, and role as claims — no DB lookup needed per request."

### Q: "Why two separate authentication filters?"

> **A:** "JwtAuthFilter handles human/browser clients using Bearer tokens. ApiKeyAuthFilter handles machine-to-machine integrations (CI/CD, Zapier, webhooks) using the Authorization: ApiKey tf_xxx header. Both filters are stateless and independent — if neither header is present, the request passes through and Spring's anyRequest().authenticated() rule returns 401 for protected routes. Registering them as beans (not @Components) prevents Spring Boot from auto-registering them in the Servlet chain, which would cause double-execution."

### Q: "How does tenant isolation work with JWT?"

> **A:** "The JWT contains a tenantId claim. JwtAuthenticationFilter extracts it and calls TenantContextHolder.setTenantId(tenantId) before passing the request to controllers. Downstream, TenantConnectionInterceptor runs SET LOCAL app.current_tenant_id = '<uuid>' on every DB connection before the transaction starts. PostgreSQL RLS policies then automatically filter every SELECT/INSERT/UPDATE to rows matching that tenant_id. The always block in JwtAuthenticationFilter clears both SecurityContext and TenantContextHolder — preventing thread-pool leakage across requests."

### Q: "Why SHA-256 for token hashing and not bcrypt?"

> **A:** "Refresh tokens and API keys are long, random, high-entropy values — unlike passwords, they don't need a slow adaptive hashing algorithm. SHA-256 is deterministic and fast, which is what you want for a lookup (hash the incoming token, query the DB by hash). BCrypt's intentional slowness is valuable for password brute-force resistance but would add unnecessary latency to every API request. The 128-bit entropy of our tf_ key format makes brute-force computationally infeasible regardless of hashing speed."

### Q: "What's the difference between RBAC at the URL level vs @PreAuthorize?"

> **A:** "URL-level rules in SecurityConfig (authorizeHttpRequests) are coarse — they can only distinguish authenticated vs unauthenticated, not role granularity per endpoint. @PreAuthorize with @EnableMethodSecurity gives per-method control. For example, GET /api-keys is ADMIN+MANAGER while DELETE /api-keys/{id} is ADMIN-only. Putting all these rules in SecurityConfig would make it a 100-line unmaintainable matcher list. @PreAuthorize keeps RBAC rules co-located with the method they protect, making them easier to audit."

---

## 📁 Phase 2 Files Delivered

### New Source Files (21 files in `/auth` package)
| File | Purpose |
|------|---------|
| `JwtService.java` | Token generation, parsing, claim extraction |
| `JwtAuthenticationFilter.java` | Bearer token validation, SecurityContext population |
| `ApiKeyService.java` | Key create/list/revoke/validateAndTouch |
| `ApiKeyAuthenticationFilter.java` | ApiKey header validation |
| `ApiKeyController.java` | REST: POST/GET/DELETE /api-keys |
| `AuthService.java` | signup/login/refresh/logout/switchTenant business logic |
| `AuthController.java` | REST: /auth/* endpoints |
| `entity/RefreshToken.java` | Refresh token entity (hash-only storage) |
| `entity/ApiKey.java` | API key entity (hash-only storage) |
| `repository/RefreshTokenRepository.java` | JPQL bulk-revoke query |
| `repository/ApiKeyRepository.java` | findByKeyHash lookup |
| `util/TokenHashUtil.java` | SHA-256 hashing utility |
| `dto/SignupRequest.java` | Input validation DTO |
| `dto/LoginRequest.java` | Input validation DTO |
| `dto/AuthResponse.java` | Token pair + tenant list response |
| `dto/TenantSummary.java` | Tenant info embedded in auth response |
| `dto/RefreshRequest.java` | Refresh token input |
| `dto/RefreshResponse.java` | New token pair after rotation |
| `dto/ApiKeyCreateRequest.java` | Key name input |
| `dto/ApiKeyCreateResponse.java` | Key metadata + rawKey (shown once) |
| `dto/ApiKeyResponse.java` | Key metadata (no value, no hash) |

### Modified Files
| File | Change |
|------|--------|
| `config/SecurityConfig.java` | Full Phase 2 upgrade: JWT/ApiKey filters, RBAC, BCrypt |
| `config/JwtProperties.java` | New: @ConfigurationProperties for JWT config |
| `common/exception/GlobalExceptionHandler.java` | RFC 7807 ProblemDetail error responses |
| `common/exception/*.java` | 4 new domain exceptions (Email/Credentials/Token/TenantAccess) |
| `tenant/TenantFilter.java` | Updated javadoc: now a fallback filter |
| `pom.xml` | Added JJWT 0.12.5, surefire argLine for JDK 24 Mockito |

### New Test Files (4 suites, 45 tests)
| File | Tests |
|------|-------|
| `auth/JwtServiceTest.java` | 18 |
| `auth/AuthServiceTest.java` | 13 |
| `auth/AuthControllerTest.java` | 10 |
| `auth/util/TokenHashUtilTest.java` | 4 |
| **Total** | **45** |

---

> **Next Phase:** Phase 3 — Projects, Tasks, Comments, Labels CRUD with cursor-based pagination, optimistic locking, and full-text search.
