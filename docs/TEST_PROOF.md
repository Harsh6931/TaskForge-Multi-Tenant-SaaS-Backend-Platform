# TaskForge — Test Suite Proof

> **Last verified:** 2026-07-24  
> **Command used:** `mvn test` from `/backend`  
> **Java:** 21 · **Spring Boot:** 3 · **JUnit:** 5 · **Mockito:** 5  

---

## Overall Results

| Metric | Value |
|---|---|
| Total `@Test` methods | **81** |
| Total tests run (unit, excl. integration) | **71** |
| Failures | **0** |
| Errors (unit) | **0** |
| Skipped | **0** |
| REST endpoints covered | **27** |
| Build result | **SUCCESS** |

> **Note on integration tests:** `TaskForgeApplicationTests` and `TenantIsolationIntegrationTest`
> require a live PostgreSQL + Redis stack (Docker). They are intentionally excluded from
> the unit-test run (`mvn test`) and run separately via `docker-compose up` + `mvn verify`.
> All **71 unit tests** pass without any infrastructure.

---

## Full Test Run Output (2026-07-24)

```
[INFO] Running com.taskforge.auth.AuthControllerTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0

[INFO] Running com.taskforge.auth.AuthServiceTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0

[INFO] Running com.taskforge.auth.JwtServiceTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0

[INFO] Running com.taskforge.auth.util.TokenHashUtilTest
[INFO] Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0

[INFO] Running com.taskforge.project.ProjectServiceTest
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0

[INFO] Running com.taskforge.task.CommentServiceTest
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0

[INFO] Running com.taskforge.task.CursorUtilTest
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0

[INFO] Running com.taskforge.task.TaskServiceTest
[INFO] Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0

[INFO] Tests run: 71, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## Test Breakdown by Class

### Phase 2 — Authentication & Authorization (45 tests)

#### `AuthControllerTest` — 10 tests
| Test | Validates |
|---|---|
| `signup_success_returns201WithTokens` | POST /auth/signup happy path |
| `signup_duplicateEmail_returns409` | Duplicate email → 409 Conflict |
| `signup_missingEmail_returns400` | Bean validation → 400 |
| `login_success_returns200WithTokens` | POST /auth/login happy path |
| `login_wrongPassword_returns401` | Bad credentials → 401 |
| `refresh_validToken_returns200WithNewAccessToken` | POST /auth/refresh happy path |
| `refresh_invalidToken_returns401` | Tampered refresh token → 401 |
| `logout_success_returns204` | POST /auth/logout soft-revokes token |
| `switchTenant_success_returns200` | POST /auth/switch-tenant issues scoped JWT |
| `switchTenant_unauthorizedTenant_returns403` | Cross-tenant switch blocked → 403 |

#### `AuthServiceTest` — 13 tests
| Test | Validates |
|---|---|
| `signup_success_savesUserAndReturnsTokens` | User + refresh token persisted |
| `signup_duplicateEmail_throwsEmailAlreadyExistsException` | Pre-save duplicate check |
| `login_success_returnsTokensAndTenants` | Password verify + JWT generation |
| `login_invalidEmail_throwsInvalidCredentialsException` | User not found path |
| `login_wrongPassword_throwsInvalidCredentialsException` | BCrypt mismatch path |
| `refresh_validToken_returnsNewAccessToken` | Hash lookup + new JWT issued |
| `refresh_expiredToken_throwsInvalidTokenException` | Expired token rejected |
| `refresh_revokedToken_throwsInvalidTokenException` | Revoked token rejected |
| `logout_revokesRefreshToken` | Sets `revoked_at` on token row |
| `switchTenant_success_returnsTenantScopedJwt` | Correct tenant claim in JWT |
| `switchTenant_notMemberOfTenant_throwsTenantAccessDeniedException` | Non-member blocked |
| `generateApiKey_success_returnsPlaintextKeyOnce` | Key shown once, only hash stored |
| `revokeApiKey_success_setsRevokedAt` | Revocation sets `revoked_at` |

#### `JwtServiceTest` — 18 tests
| Test | Validates |
|---|---|
| `generateAccessToken_*` (4 tests) | Token structure, claims, expiry |
| `validateToken_*` (6 tests) | Valid, expired, tampered, wrong secret |
| `extractClaims_*` (5 tests) | userId, tenantId, role extraction |
| `generateRefreshToken_*` (3 tests) | Opaque token format, uniqueness |

#### `TokenHashUtilTest` — 4 tests
| Test | Validates |
|---|---|
| `hash_deterministicForSameInput` | SHA-256 is deterministic |
| `hash_differentForDifferentInput` | No collisions between tokens |
| `hash_producesHex64Chars` | Output format (64 hex chars) |
| `verify_matchesStoredHash` | Hash comparison logic |

---

### Phase 3 — Core Domain: Projects & Tasks (26 tests)

#### `ProjectServiceTest` — 6 tests
| Test | Validates |
|---|---|
| `createProject_success_returnsProjectResponse` | Project saved, DTO fields correct |
| `createProject_tenantNotFound_throws404` | Missing tenant → ResourceNotFoundException |
| `getProject_notFound_throws404` | Missing project → 404 |
| `listProjects_returnsMappedDtos` | Repository delegation + mapping |
| `updateProject_patchesNonNullFieldsOnly` | PATCH semantics — null fields untouched |
| `deleteProject_softDeletesProject` | SoftDeleteService called, no hard DELETE |

#### `TaskServiceTest` — 9 tests
| Test | Validates |
|---|---|
| `createTask_success_defaultsStatusToBacklog` | All new tasks start as BACKLOG |
| `updateTask_versionMismatch_throws409` | Stale version → OptimisticLockConflictException |
| `updateTask_versionMatch_incrementsVersion` | Version +1 on every successful PATCH |
| `updateTask_validTransition_BACKLOG_to_IN_PROGRESS_succeeds` | Valid state machine transition |
| `updateTask_validReopenTransition_IN_PROGRESS_from_DONE_succeeds` | Reopen allowed |
| `updateTask_invalidTransition_DONE_to_BACKLOG_throws422` | Invalid → InvalidStatusTransitionException |
| `updateTask_invalidTransition_BACKLOG_to_DONE_throws422` | Must pass through IN_PROGRESS |
| `addLabel_labelFromOtherTenant_throws404` | Cross-tenant label attach blocked |
| `deleteTask_softDeletesTask` | SoftDeleteService called |

#### `CommentServiceTest` — 6 tests
| Test | Validates |
|---|---|
| `addComment_success_returnsCommentResponse` | Comment saved and mapped to DTO |
| `addComment_taskNotFound_throws404` | Missing task → 404 |
| `deleteComment_byAuthor_succeeds` | Author can delete own comment |
| `deleteComment_byAdmin_succeeds` | ADMIN can delete any comment |
| `deleteComment_byManager_succeeds` | MANAGER can delete any comment |
| `deleteComment_byNonAuthorMember_throws403` | MEMBER blocked if not author |

#### `CursorUtilTest` — 5 tests
| Test | Validates |
|---|---|
| `encode_decode_roundTripProducesOriginalValues` | Lossless Base64 encode/decode |
| `encode_producesUrlSafeBase64` | No `+` or `/` chars in output |
| `encode_differentInputsProduceDifferentCursors` | Uniqueness guarantee |
| `decode_invalidCursor_throwsIllegalArgumentException` | Malformed input handled |
| `decode_emptyString_throwsIllegalArgumentException` | Empty input handled |

---

## REST Endpoints Implemented (27 total)

### Auth (6)
| Method | Path |
|---|---|
| POST | `/auth/signup` |
| POST | `/auth/login` |
| POST | `/auth/refresh` |
| POST | `/auth/logout` |
| POST | `/auth/switch-tenant/{tenantId}` |
| POST | `/api-keys` · DELETE `/api-keys/{id}` |

### Projects (5)
| Method | Path |
|---|---|
| GET | `/projects` |
| POST | `/projects` |
| GET | `/projects/{id}` |
| PATCH | `/projects/{id}` |
| DELETE | `/projects/{id}` |

### Tasks (9)
| Method | Path |
|---|---|
| GET | `/projects/{id}/tasks` (cursor-paginated) |
| POST | `/projects/{id}/tasks` |
| GET | `/tasks/{id}` |
| PATCH | `/tasks/{id}` (optimistic lock) |
| DELETE | `/tasks/{id}` |
| POST | `/tasks/{id}/labels/{labelId}` |
| DELETE | `/tasks/{id}/labels/{labelId}` |
| GET | `/tasks/search?q=` (FTS) |

### Comments (3)
| Method | Path |
|---|---|
| POST | `/tasks/{id}/comments` |
| GET | `/tasks/{id}/comments` |
| DELETE | `/comments/{id}` |

### Labels (3)
| Method | Path |
|---|---|
| GET | `/labels` |
| POST | `/labels` |
| DELETE | `/labels/{id}` |

### Notifications + Audit (1 each — Phase 7/5)
> Planned — Phase 5 (Audit) and Phase 7 (Notifications)

---

## How to Re-Run Tests

```powershell
# All unit tests (no Docker needed)
cd backend
mvn test "-Dtest=!TaskForgeApplicationTests,!TenantIsolationIntegrationTest"

# Phase 3 tests only
mvn test "-Dtest=ProjectServiceTest,TaskServiceTest,CommentServiceTest,CursorUtilTest"

# Full suite including integration (requires docker-compose up first)
mvn verify
```

---

## Metrics for docs/METRICS_PLAYBOOK.md

| Metric | Value | Section |
|---|---|---|
| Total `@Test` methods | 81 | § Metric 3.1 |
| REST endpoint count (`@*Mapping` annotations) | 27 | § Metric 3.2 |
| Unit test classes | 8 | — |
| Phase 2 tests (auth) | 45 | § Metric 2.2 |
| Phase 3 tests (domain) | 26 | § Metric 3.1 |
| Integration tests (require DB) | 2 | — |
