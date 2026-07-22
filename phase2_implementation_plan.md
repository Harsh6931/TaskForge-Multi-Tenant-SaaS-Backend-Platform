# Phase 2 — Authentication & Authorization
### Implementation Breakdown (Small Learning Goals)

> **Current state (entering Phase 2):** Phase 1 is fully complete. We have:
> - 7 Flyway migrations (V1–V7) — full schema + RLS enabled on all tenant-scoped tables
> - `TenantContextHolder`, `TenantFilter`, `TenantConnectionInterceptor` — tenant injection pipeline
> - JPA entities: `Tenant`, `User`, `TenantUser` (with `TenantUserRole` enum), `Project`
> - Repositories: `TenantRepository`, `UserRepository`, `TenantUserRepository`, `ProjectRepository`
> - `SecurityConfig` placeholder — currently allows all requests (`permitAll()`) — **Phase 2 replaces this**
> - All JJWT dependencies already in `pom.xml` — JWT infrastructure is ready to wire
> - Tables ready: `users`, `tenant_users`, `refresh_tokens`, `api_keys` already exist in the schema

> **Phase 2 Goal:** Replace the `permitAll()` placeholder with a fully working, production-grade auth system — stateless JWT access tokens, secure refresh tokens, tenant-aware login with workspace switching, RBAC on every endpoint, and per-tenant API keys.

---

## 🗂️ Overview Map

```
Goal 1 → Understand JWT — how tokens work, what's inside them, why stateless
Goal 2 → Build JwtService — the token factory and validator
Goal 3 → Build the auth entities + repositories (RefreshToken, ApiKey)
Goal 4 → Build AuthService — signup, login, refresh, logout logic
Goal 5 → Build AuthController — the /auth/* REST endpoints
Goal 6 → Build JwtAuthenticationFilter — the per-request token verifier
Goal 7 → Upgrade SecurityConfig — replace permitAll() with real JWT rules
Goal 8 → Implement tenant-switch endpoint — /auth/switch-tenant/{tenantId}
Goal 9 → Implement RBAC — @PreAuthorize on endpoints matching your RBAC matrix
Goal 10 → Implement API key auth — per-tenant programmatic access
Goal 11 → Write the test suite — auth flows + role restriction + API key tests
```

---

## Goal 1 — Understand JWT (The Mental Model First)

> **Why first?** You will write ~10 classes this phase. If you don't understand the JWT lifecycle, you'll cargo-cult the code and won't be able to explain it in an interview.

### What to learn
- **What is a JWT?** Three Base64-encoded parts: `header.payload.signature`
  - Header: algorithm (`HS256` or `RS256`) + type
  - Payload (claims): `sub` (subject = userId), `tenantId`, `role`, `iat` (issued at), `exp` (expires at)
  - Signature: HMAC-SHA256 of header + payload, signed with your secret key — proves the token wasn't tampered with
- **Stateless auth:** The server stores NO session. Every request carries its own token. The server just validates the signature and reads the claims.
- **Access token vs refresh token:**
  - Access token: short-lived (15 min), sent with every API call in `Authorization: Bearer <token>`
  - Refresh token: long-lived (7 days), stored as a hash in `refresh_tokens` table, used only to get a new access token
  - Why two tokens? Short-lived access token limits the damage if it's stolen. Refresh token can be revoked server-side.
- **Token rotation:** On each `/auth/refresh` call, the old refresh token is revoked (marked `revoked_at`) and a new one is issued. This prevents replay attacks.

### Key concept to internalize
> *"A JWT is a signed certificate, not a session ticket. The signature is what makes it trustworthy — anyone can decode the claims (they're Base64, not encrypted), but only the server with the secret key can create a valid signature. Never put passwords or sensitive PII in a JWT payload."*

### Interview talking point
> *"I use short-lived access tokens (15 min) with refresh token rotation — each refresh issues a new refresh token and immediately revokes the old one. This means a stolen refresh token can only be used once before the legitimate user's next refresh invalidates it."*

---

## Goal 2 — JwtService (The Token Factory)

> **Why this first?** Every other auth component depends on this service. Build and test it in isolation before touching controllers or filters.

### What to build

#### 2a — JWT configuration properties
Add to `application.yml`:
```yaml
app:
  jwt:
    secret: <long-random-base64-string-at-least-256-bits>  # loaded from env in prod
    access-token-expiry-ms: 900000      # 15 minutes
    refresh-token-expiry-ms: 604800000  # 7 days
```

Create a `@ConfigurationProperties` class to bind these:
```java
// com/taskforge/config/JwtProperties.java
@ConfigurationProperties(prefix = "app.jwt")
@Data
public class JwtProperties {
    private String secret;
    private long accessTokenExpiryMs;
    private long refreshTokenExpiryMs;
}
```
Enable with `@EnableConfigurationProperties(JwtProperties.class)` on `TaskForgeApplication`.

#### 2b — JwtService
```java
// com/taskforge/auth/JwtService.java
@Service
public class JwtService {
    // generateAccessToken(UUID userId, UUID tenantId, TenantUserRole role) → String
    // generateRefreshTokenValue() → String  (random UUID, stored hashed in DB)
    // parseAccessToken(String token) → Claims
    // extractUserId(Claims) → UUID
    // extractTenantId(Claims) → UUID
    // extractRole(Claims) → TenantUserRole
    // isTokenExpired(Claims) → boolean
}
```

**Key implementation notes:**
- Use JJWT 0.12.x API (`Jwts.builder()`, `Jwts.parser()`) — already in `pom.xml`
- Sign with `Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret))` — the secret must be ≥256 bits for HS256
- Claims to put in access token: `sub` = userId, `tenantId` = tenantId, `role` = role name
- Refresh token value is just a `UUID.randomUUID().toString()` — you store the **SHA-256 hash** of it in DB

### Files to create
- `com/taskforge/config/JwtProperties.java`
- `com/taskforge/auth/JwtService.java`

### What to learn
- Why `Keys.hmacShaKeyFor()` instead of a raw string? JJWT enforces key length security.
- Why store refresh token as SHA-256 hash? Same principle as password hashing — if the DB leaks, tokens can't be used.

---

## Goal 3 — Auth Entities + Repositories

> **Why a separate goal?** You need two new entities — `RefreshToken` and `ApiKey`. The schema tables already exist (V4 migration). This goal is the Java mirror step, like Goal 5 in Phase 1.

### Entities to build

#### 3a — RefreshToken entity
Maps to the `refresh_tokens` table from `V4__create_auth_and_keys.sql`:
```java
// com/taskforge/auth/entity/RefreshToken.java
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {
    UUID id;
    @ManyToOne User user;      // FK to users.id
    String tokenHash;          // SHA-256 hash of the raw token
    Instant expiresAt;
    Instant createdAt;
    Instant revokedAt;         // null = active, set = revoked
    // No tenant_id — refresh tokens are user-scoped, not tenant-scoped
    // No soft-delete — revokedAt serves the same purpose
}
```

#### 3b — ApiKey entity
Maps to the `api_keys` table:
```java
// com/taskforge/auth/entity/ApiKey.java
@Entity
@Table(name = "api_keys")
@Where(clause = "revoked_at IS NULL")
public class ApiKey {
    UUID id;
    UUID tenantId;             // which tenant this key belongs to
    String keyHash;            // SHA-256 hash of the raw key
    String name;               // human-readable label, e.g. "CI/CD Pipeline"
    Instant createdAt;
    Instant lastUsedAt;        // updated on each successful use
    Instant revokedAt;         // null = active
}
```

### Repositories to build
```java
// com/taskforge/auth/repository/RefreshTokenRepository.java
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void revokeAllByUserId(UUID userId);  // for logout-everywhere
}

// com/taskforge/auth/repository/ApiKeyRepository.java
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHash(String keyHash);
    List<ApiKey> findAllByTenantId(UUID tenantId);
}
```

### Files to create
- `com/taskforge/auth/entity/RefreshToken.java`
- `com/taskforge/auth/entity/ApiKey.java`
- `com/taskforge/auth/repository/RefreshTokenRepository.java`
- `com/taskforge/auth/repository/ApiKeyRepository.java`

---

## Goal 4 — AuthService (The Business Logic Core)

> **Why separate from the controller?** Service layer is unit-testable without HTTP. This is the most business-logic-dense class in Phase 2 — keep it clean and focused.

### DTOs to create first
```java
// com/taskforge/auth/dto/SignupRequest.java
record SignupRequest(
    @Email String email,
    @NotBlank @Size(min=8) String password,
    @NotBlank String fullName
) {}

// com/taskforge/auth/dto/LoginRequest.java
record LoginRequest(@Email String email, @NotBlank String password) {}

// com/taskforge/auth/dto/AuthResponse.java
record AuthResponse(
    String accessToken,
    String refreshToken,
    List<TenantSummary> tenants  // user's tenant memberships
) {}

// com/taskforge/auth/dto/TenantSummary.java
record TenantSummary(UUID tenantId, String name, String slug, TenantUserRole role) {}

// com/taskforge/auth/dto/RefreshRequest.java
record RefreshRequest(@NotBlank String refreshToken) {}
```

### AuthService methods

```java
// com/taskforge/auth/AuthService.java
@Service
@Transactional
public class AuthService {

    // signup(SignupRequest) → AuthResponse
    //   1. Check email not already taken (throw 409 if it is)
    //   2. Create User with BCryptPasswordEncoder.encode(password)
    //   3. (No tenant created here — user joins tenants separately in Phase 3)
    //   4. Issue access token (tenantId=null, role=null for fresh signup)
    //   5. Issue refresh token, store hash in DB
    //   6. Return AuthResponse

    // login(LoginRequest) → AuthResponse
    //   1. Load user by email (throw 401 if not found)
    //   2. BCryptPasswordEncoder.matches(rawPassword, passwordHash) (throw 401 if no match)
    //   3. Load all TenantUser entries for this user → build TenantSummary list
    //   4. Issue access token scoped to the FIRST tenant (or no tenant if user has none)
    //   5. Issue + store refresh token
    //   6. Return AuthResponse with tenants list

    // refresh(RefreshRequest) → { accessToken }
    //   1. SHA-256 hash the incoming raw token
    //   2. Look up RefreshToken by hash (throw 401 if not found or revokedAt != null)
    //   3. Check expiresAt (throw 401 if expired)
    //   4. Rotate: set revokedAt=now() on old token, create new refresh token
    //   5. Re-issue access token with same userId + tenantId + role from old token's user
    //   6. Return new accessToken + new refreshToken

    // logout(UUID userId)
    //   1. Set revokedAt=now() on ALL refresh tokens for this userId (logout-everywhere)

    // Helper: issueRefreshToken(User user) → String (raw token)
    //   1. Generate raw = UUID.randomUUID().toString()
    //   2. Hash it: SHA-256(raw)
    //   3. Save RefreshToken entity with hash, expiresAt = now + 7 days
    //   4. Return raw (sent to client, never stored)
}
```

### Key implementation notes
- Use `PasswordEncoder` bean (BCrypt) — define as a `@Bean` in a new `AuthConfig` or `SecurityConfig`
- The `SHA-256` hash: `MessageDigest.getInstance("SHA-256")` from `java.security`
- Why BCrypt for passwords but SHA-256 for tokens? BCrypt is intentionally slow (for brute-force resistance). Tokens are random high-entropy values — SHA-256 is fast enough and sufficient because there's nothing to brute-force.

### Files to create
- `com/taskforge/auth/dto/SignupRequest.java`
- `com/taskforge/auth/dto/LoginRequest.java`
- `com/taskforge/auth/dto/AuthResponse.java`
- `com/taskforge/auth/dto/TenantSummary.java`
- `com/taskforge/auth/dto/RefreshRequest.java`
- `com/taskforge/auth/AuthService.java`

---

## Goal 5 — AuthController (The REST Endpoints)

> **Why a separate goal?** Controller is purely a translation layer — request body → service call → response. Keeping it thin is a discipline you want to practice.

### Endpoints to build

```
POST /auth/signup          → AuthResponse (201 Created)
POST /auth/login           → AuthResponse (200 OK)
POST /auth/refresh         → { accessToken, refreshToken } (200 OK)
POST /auth/logout          → 204 No Content
```

### Controller pattern
```java
// com/taskforge/auth/AuthController.java
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    // POST /auth/signup
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest request) {...}

    // POST /auth/login
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {...}

    // POST /auth/refresh
    @PostMapping("/refresh")
    public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {...}

    // POST /auth/logout  (requires valid access token — handled by filter)
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(/* extract userId from SecurityContext */) {...}
}
```

### Error handling
Create a `GlobalExceptionHandler` with `@RestControllerAdvice`:
- `EmailAlreadyExistsException` → 409 Conflict
- `InvalidCredentialsException` → 401 Unauthorized
- `TokenExpiredException` / `TokenRevokedException` → 401 Unauthorized
- `MethodArgumentNotValidException` (Bean Validation) → 400 Bad Request with field errors

### Files to create
- `com/taskforge/auth/AuthController.java`
- `com/taskforge/auth/dto/RefreshResponse.java`
- `com/taskforge/common/exception/EmailAlreadyExistsException.java`
- `com/taskforge/common/exception/InvalidCredentialsException.java`
- `com/taskforge/common/exception/TokenExpiredException.java`
- `com/taskforge/common/exception/GlobalExceptionHandler.java`

---

## Goal 6 — JwtAuthenticationFilter (Per-Request Token Validation)

> **Why a separate goal?** This filter is the gatekeeper for every API call. It reads the token, validates it, and puts the user's identity into Spring Security's `SecurityContext`. Get this wrong and nothing else works.

### What to build

```
HTTP Request
    │
    ▼
[JwtAuthenticationFilter]              ← reads "Authorization: Bearer <token>"
    │  1. Extract token from header
    │  2. Parse + validate with JwtService (throws if expired/invalid)
    │  3. Build UsernamePasswordAuthenticationToken
    │     { principal: userId, credentials: null, authorities: [ROLE_ADMIN] }
    │  4. SecurityContextHolder.getContext().setAuthentication(auth)
    │
    ▼  (also update TenantContextHolder with tenantId from claims)
[TenantFilter]                         ← already exists from Phase 1
    │  ← Phase 2 upgrade: reads tenantId from SecurityContext/JWT claims
    │    instead of raw X-Tenant-ID header
    ▼
[Repository / JPA]                     ← RLS reads tenant from DB session variable
```

### Implementation
```java
// com/taskforge/auth/JwtAuthenticationFilter.java
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(...) {
        // 1. Skip if no Authorization header or doesn't start with "Bearer "
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. Extract and parse token
        String token = header.substring(7);
        Claims claims = jwtService.parseAccessToken(token);  // throws on invalid

        // 3. Set authentication in SecurityContext
        UUID userId = jwtService.extractUserId(claims);
        TenantUserRole role = jwtService.extractRole(claims);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 4. Set tenant context (upgrade from Phase 1's X-Tenant-ID header approach)
        UUID tenantId = jwtService.extractTenantId(claims);
        TenantContextHolder.setTenantId(tenantId);

        filterChain.doFilter(request, response);
    }
}
```

> **Phase 2 upgrade to TenantFilter:** The Phase 1 `TenantFilter` read `X-Tenant-ID` from the header as a placeholder. In Phase 2, the JWT already contains `tenantId` — the `JwtAuthenticationFilter` sets `TenantContextHolder` directly. The `TenantConnectionInterceptor` still runs `SET LOCAL app.current_tenant_id = ?` from the holder, unchanged.

### Files to create
- `com/taskforge/auth/JwtAuthenticationFilter.java`

---

## Goal 7 — Upgrade SecurityConfig

> **Why a dedicated goal?** This is the most error-prone file in Spring Security. One wrong configuration and either everything is blocked or nothing is protected. Work through it step by step.

### What changes from Phase 1

**Before (Phase 1 placeholder):**
```java
.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
```

**After (Phase 2 — production JWT rules):**
```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // enables @PreAuthorize on service/controller methods
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                // Public endpoints — no token needed
                .requestMatchers("/auth/signup", "/auth/login", "/auth/refresh").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            // Register JWT filter BEFORE the default UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService);
    }
}
```

### `@EnableMethodSecurity` — what it enables
This annotation allows `@PreAuthorize("hasRole('ADMIN')")` on individual controller methods or service methods. This is how RBAC is implemented in Goal 9 — not in `SecurityConfig` (which would be too coarse-grained), but on each endpoint.

### Files to modify
- `com/taskforge/config/SecurityConfig.java` — replace `permitAll()` with JWT filter chain

---

## Goal 8 — Tenant Switch Endpoint

> **Why a separate goal?** This is a unique multi-tenant auth pattern you'll explain in every interview. Slack, Notion, Linear all work this way — one user, multiple workspaces, a token per workspace session.

### The problem it solves
After login, the user gets a token scoped to their "default" tenant. But if they belong to 3 tenants (3 workspaces), they need a way to get a token scoped to a different tenant. That's what `/auth/switch-tenant` does.

### Endpoint
```
POST /auth/switch-tenant/{tenantId}
Authorization: Bearer <current access token>
→ Response: { accessToken: <new token scoped to tenantId>, refreshToken: <new refresh token> }
```

### Implementation in AuthService
```java
// switchTenant(UUID userId, UUID targetTenantId) → AuthResponse
//   1. Verify the authenticated user is a member of targetTenantId
//      (look up TenantUser by userId + tenantId)
//   2. If not a member → throw 403 Forbidden
//   3. Issue a new access token with targetTenantId + user's role in that tenant
//   4. Issue new refresh token (rotate — invalidate old one)
//   5. Return new AuthResponse
```

### Interview talking point
> *"The login response returns a list of all tenant memberships. The frontend shows a workspace switcher. When the user switches, the client calls `/auth/switch-tenant/{id}` and stores the new tenant-scoped token. All subsequent API calls use this token — the JWT carries the tenantId so RLS automatically filters to the right workspace."*

### Files to modify
- `com/taskforge/auth/AuthService.java` — add `switchTenant()` method
- `com/taskforge/auth/AuthController.java` — add `POST /auth/switch-tenant/{tenantId}`

---

## Goal 9 — RBAC with @PreAuthorize

> **Why important?** This is what separates "I check roles in my service" from "Spring Security enforces roles declaratively." Interviewers notice the difference.

### Your RBAC matrix (from `docs/RBAC.md` — 🧠 you designed this)

| Action | ADMIN | MANAGER | MEMBER | VIEWER |
|---|---|---|---|---|
| Invite user to tenant | ✅ | ❌ | ❌ | ❌ |
| Remove user from tenant | ✅ | ❌ | ❌ | ❌ |
| Change user role | ✅ | ❌ | ❌ | ❌ |
| Create project | ✅ | ✅ | ❌ | ❌ |
| Delete project | ✅ | ❌ | ❌ | ❌ |
| Create/update task | ✅ | ✅ | ✅ | ❌ |
| Delete task | ✅ | ✅ | ❌ | ❌ |
| View tasks/projects | ✅ | ✅ | ✅ | ✅ |
| Generate API key | ✅ | ❌ | ❌ | ❌ |
| View audit logs | ✅ | ❌ | ❌ | ❌ |
| Manage billing | ✅ | ❌ | ❌ | ❌ |

> ⚠️ **Note:** The table above is a SUGGESTED matrix. You (🧠) should review and finalize it in `docs/RBAC.md` before Phase 2 implementation begins. The agent will implement exactly what you write there.

### How to apply with @PreAuthorize
```java
// Example on a future Phase 3 ProjectController:
@PostMapping
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public ResponseEntity<ProjectResponse> createProject(...) { ... }

@DeleteMapping("/{id}")
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<Void> deleteProject(...) { ... }

@GetMapping
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'MEMBER', 'VIEWER')")
public ResponseEntity<List<ProjectResponse>> listProjects(...) { ... }
```

### Goal 9 deliverable (Phase 2 scope)
Apply `@PreAuthorize` to the auth-related endpoints that need role protection:
- `POST /auth/switch-tenant/{tenantId}` — requires `authenticated()`
- `POST /auth/logout` — requires `authenticated()`

Phase 3 will add `@PreAuthorize` to project/task endpoints. The **important thing in Phase 2** is:
1. `@EnableMethodSecurity` is turned on in `SecurityConfig` ✅
2. You understand the annotation and can apply it — it's ready to use in Phase 3

### Files to modify
- `com/taskforge/config/SecurityConfig.java` — add `@EnableMethodSecurity`

---

## Goal 10 — API Key Authentication

> **Why include this?** API keys are how B2B SaaS products let customers automate — CI/CD pipelines, webhooks, integrations. Showing you built this demonstrates you understand real-world SaaS auth patterns.

### How it works
```
Request with ApiKey:
Authorization: ApiKey tf_live_abc123xyz789...

[ApiKeyAuthenticationFilter]
    │  1. Check header starts with "ApiKey " (not "Bearer ")
    │  2. SHA-256 hash the raw key value
    │  3. Look up ApiKey entity by keyHash
    │  4. Check revokedAt == null (not revoked)
    │  5. Update lastUsedAt = now() (async or best-effort)
    │  6. Set Authentication in SecurityContext
    │     { principal: tenantId, authorities: [ROLE_API_KEY] }
    │  7. Set TenantContextHolder.setTenantId(apiKey.getTenantId())
    ▼
[Normal request processing with RLS active]
```

### Endpoints
```
POST   /api-keys          { name } → { id, name, key }  (raw key shown ONCE — never again)
DELETE /api-keys/{id}     → 204 No Content (sets revokedAt)
GET    /api-keys          → [ { id, name, createdAt, lastUsedAt } ]  (no key values)
```

### Implementation
```java
// com/taskforge/auth/ApiKeyService.java
@Service
public class ApiKeyService {

    // generateApiKey(UUID tenantId, String name) → { ApiKey entity, rawKeyString }
    //   1. Generate raw key: "tf_" + UUID.randomUUID().toString().replace("-", "")
    //      Result: "tf_550e8400e29b41d4a716446655440000" (recognizable prefix, opaque value)
    //   2. SHA-256 hash the raw key
    //   3. Save ApiKey entity with hash, tenantId, name
    //   4. Return BOTH the entity and rawKeyString (raw is shown once, then gone)

    // revokeApiKey(UUID keyId, UUID tenantId)
    //   1. Load ApiKey by id, verify tenantId matches (prevent tenant A revoking tenant B's keys)
    //   2. Set revokedAt = now()

    // listApiKeys(UUID tenantId) → List<ApiKeyResponse>
    //   Returns metadata only — never the key hash or raw value
}

// com/taskforge/auth/ApiKeyAuthenticationFilter.java
// Runs in filter chain alongside JwtAuthenticationFilter
// Checks "Authorization: ApiKey ..." header
// If present: hash → lookup → authenticate
// If absent: skip (let JwtAuthenticationFilter handle it)
```

### Files to create
- `com/taskforge/auth/ApiKeyService.java`
- `com/taskforge/auth/ApiKeyController.java`
- `com/taskforge/auth/ApiKeyAuthenticationFilter.java`
- `com/taskforge/auth/dto/ApiKeyCreateRequest.java`
- `com/taskforge/auth/dto/ApiKeyCreateResponse.java` — includes raw key (shown once)
- `com/taskforge/auth/dto/ApiKeyResponse.java` — metadata only (for list endpoint)

---

## Goal 11 — Test Suite

> **Phase 2 deliverable:** Every auth flow tested. You'll be able to say: "I have [N] test cases covering auth paths, including role boundary tests and token rotation."

### Test classes to write

#### 11a — AuthServiceTest (Unit Test)
```java
// src/test/java/com/taskforge/auth/AuthServiceTest.java
// Use @ExtendWith(MockitoExtension.class) — no Spring context needed
// Mock: UserRepository, TenantUserRepository, RefreshTokenRepository, JwtService, PasswordEncoder

// Test cases:
// signup_success_createsUserAndReturnsTokens()
// signup_duplicateEmail_throws409()
// login_success_returnsTokensAndTenantList()
// login_wrongPassword_throws401()
// login_userNotFound_throws401()
// refresh_validToken_rotatesAndReturnsNewTokens()
// refresh_revokedToken_throws401()
// refresh_expiredToken_throws401()
// logout_revokesAllRefreshTokens()
// switchTenant_memberOfTenant_issuesnewToken()
// switchTenant_notMemberOfTenant_throws403()
```

#### 11b — AuthControllerTest (Integration Test / MockMvc)
```java
// src/test/java/com/taskforge/auth/AuthControllerTest.java
// Use @WebMvcTest(AuthController.class) + @MockBean for services
// Test HTTP layer: status codes, response shapes, validation errors

// Test cases:
// POST /auth/signup → 201 with tokens
// POST /auth/signup with missing fields → 400 with validation errors
// POST /auth/login → 200 with tokens + tenants
// POST /auth/login with wrong password → 401
// POST /auth/refresh → 200 with new tokens
// POST /auth/logout without token → 401
// POST /auth/switch-tenant/{id} without token → 401
```

#### 11c — JwtServiceTest (Unit Test)
```java
// src/test/java/com/taskforge/auth/JwtServiceTest.java
// No mocks needed — pure logic test

// Test cases:
// generateAccessToken_producesValidJwt()
// parseAccessToken_validToken_extractsCorrectClaims()
// parseAccessToken_expiredToken_throwsExpiredException()
// parseAccessToken_tamperedToken_throwsSignatureException()
// generateRefreshTokenValue_producesUniqueValues()
```

#### 11d — RBAC Endpoint Tests (Integration Test)
```java
// src/test/java/com/taskforge/auth/RbacTest.java
// Tests that role-based access is actually enforced

// Test cases:
// adminUser_canAccessAdminEndpoints()
// viewerUser_cannotAccessManagerEndpoints_returns403()
// unauthenticatedUser_cannotAccessProtectedEndpoints_returns401()
// apiKeyAuth_canAccessApiRoutes()
// apiKeyAuth_withRevokedKey_returns401()
```

### Files to create
- `src/test/java/com/taskforge/auth/AuthServiceTest.java`
- `src/test/java/com/taskforge/auth/AuthControllerTest.java`
- `src/test/java/com/taskforge/auth/JwtServiceTest.java`
- `src/test/java/com/taskforge/auth/RbacTest.java`

---

## Phase 2 Completion Checklist

| Goal | Description | Status |
|---|---|---|
| Goal 1 | JWT mental model internalized (no code) | ✅ |
| Goal 2 | JwtService — token generation + validation | ✅ |
| Goal 3 | RefreshToken + ApiKey entities + repositories | ✅ |
| Goal 4 | AuthService — signup, login, refresh, logout logic | ✅ |
| Goal 5 | AuthController — /auth/* REST endpoints | ✅ |
| Goal 6 | JwtAuthenticationFilter — per-request token verification | ✅ |
| Goal 7 | SecurityConfig upgraded — JWT rules replace permitAll() | ✅ |
| Goal 8 | Tenant-switch endpoint — /auth/switch-tenant/{tenantId} | ✅ |
| Goal 9 | RBAC — @EnableMethodSecurity on + matrix in RBAC.md | ✅ |
| Goal 10 | API key generation, revocation, and auth filter | ✅ |
| Goal 11 | Full test suite — AuthServiceTest, AuthControllerTest, TokenHashUtilTest | ✅ |

---

## Package Structure After Phase 2

```
com.taskforge
├── TaskForgeApplication.java
├── common/
│   ├── BaseEntity.java
│   ├── SoftDeleteService.java
│   └── exception/
│       ├── EmailAlreadyExistsException.java
│       ├── InvalidCredentialsException.java
│       ├── TokenExpiredException.java
│       └── GlobalExceptionHandler.java
├── config/
│   ├── JpaConfig.java
│   ├── JwtProperties.java              ← NEW: @ConfigurationProperties
│   ├── SecurityConfig.java             ← UPGRADED: JWT filter chain
│   └── TenantConfig.java
├── auth/                               ← NEW PACKAGE
│   ├── AuthService.java
│   ├── AuthController.java
│   ├── JwtService.java
│   ├── JwtAuthenticationFilter.java
│   ├── ApiKeyService.java
│   ├── ApiKeyController.java
│   ├── ApiKeyAuthenticationFilter.java
│   ├── dto/
│   │   ├── SignupRequest.java
│   │   ├── LoginRequest.java
│   │   ├── AuthResponse.java
│   │   ├── TenantSummary.java
│   │   ├── RefreshRequest.java
│   │   ├── RefreshResponse.java
│   │   ├── ApiKeyCreateRequest.java
│   │   ├── ApiKeyCreateResponse.java
│   │   └── ApiKeyResponse.java
│   ├── entity/
│   │   ├── RefreshToken.java
│   │   └── ApiKey.java
│   └── repository/
│       ├── RefreshTokenRepository.java
│       └── ApiKeyRepository.java
├── tenant/
│   ├── TenantContextHolder.java
│   ├── TenantFilter.java
│   ├── TenantConnectionInterceptor.java
│   ├── entity/
│   │   └── Tenant.java
│   └── repository/
│       └── TenantRepository.java
├── user/
│   ├── entity/
│   │   ├── User.java
│   │   ├── TenantUser.java
│   │   └── TenantUserRole.java
│   └── repository/
│       ├── UserRepository.java
│       └── TenantUserRepository.java
└── project/
    ├── entity/
    │   └── Project.java
    └── repository/
        └── ProjectRepository.java
```

---

## Key Concepts Mastered After Phase 2

| Concept | Where you used it |
|---|---|
| JWT structure (header.payload.signature) | `JwtService` — generate + parse |
| HMAC-SHA256 signing | `Keys.hmacShaKeyFor()` in `JwtService` |
| Access token vs. refresh token | `AuthService.login()`, `AuthService.refresh()` |
| Refresh token rotation | `AuthService.refresh()` — revoke old, issue new |
| BCrypt password hashing | `AuthService.signup()`, `SecurityConfig.passwordEncoder()` |
| SHA-256 token/key hashing | Refresh token + API key storage |
| `OncePerRequestFilter` | `JwtAuthenticationFilter`, `ApiKeyAuthenticationFilter` |
| `SecurityContextHolder` | Storing auth for the duration of a request |
| `@EnableMethodSecurity` + `@PreAuthorize` | Role enforcement on endpoints |
| Tenant-scoped token switching | `AuthService.switchTenant()`, `/auth/switch-tenant/{id}` |
| API key prefix pattern (`tf_...`) | `ApiKeyService.generateApiKey()` |
| `@ConfigurationProperties` | `JwtProperties` — type-safe config binding |
| `@WebMvcTest` + `@MockBean` | `AuthControllerTest` |
| Mockito unit tests | `AuthServiceTest`, `JwtServiceTest` |

---

## 📏 Measurement Checkpoint (Phase 2 — collect after tests go green)

Per `docs/METRICS_PLAYBOOK.md` § Metric 2.x:

1. **Run Security Surface Scan** (Appendix D in METRICS_PLAYBOOK.md) → generates `docs/SECURITY_SURFACE.md`
   - Count total `@GetMapping`/`@PostMapping`/`@PatchMapping`/`@DeleteMapping` annotations
   - Count how many have `@PreAuthorize` or are behind JWT filter
   - Record in § Metric 2.1

2. **Count auth test methods:**
   ```powershell
   grep -rc "@Test" backend/src/test/java/com/taskforge/auth/
   ```
   Record in § Metric 2.2

3. **Resume bullet template** (fill in after numbers are real):
   > *"Secured ~[N] API endpoints across 4 RBAC roles (Admin/Manager/Member/Viewer) using Spring Security + JWT, with zero unauthorized-access failures across [X] automated test cases"*
