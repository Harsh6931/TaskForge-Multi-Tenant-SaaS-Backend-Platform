package com.taskforge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskforge.auth.dto.*;
import com.taskforge.common.exception.EmailAlreadyExistsException;
import com.taskforge.common.exception.GlobalExceptionHandler;
import com.taskforge.common.exception.InvalidCredentialsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthControllerTest — Standalone MockMvc slice test for {@link AuthController}.
 *
 * <p><b>Why Standalone Setup?</b>
 * Uses {@link MockMvcBuilders#standaloneSetup(Object...)} with {@link GlobalExceptionHandler}.
 * This avoids Spring context booting overhead and bytecode-modification issues on newer JDKs,
 * while testing the exact HTTP contract (Jackson serialization, status codes, validation,
 * and exception mapping).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController — HTTP contract tests")
class AuthControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── POST /auth/signup ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/signup: valid request → 201 with token pair and empty tenants")
    void signup_validRequest_returns201() throws Exception {
        AuthResponse stubResponse = new AuthResponse("access-token", "refresh-token", List.of());
        when(authService.signup(any(SignupRequest.class))).thenReturn(stubResponse);

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("bob@example.com", "password123", "Bob"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.tenants").isArray());
    }

    @Test
    @DisplayName("POST /auth/signup: invalid email → 400 with field errors map")
    void signup_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("not-an-email", "password123", "Bob"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    @DisplayName("POST /auth/signup: password too short → 400")
    void signup_shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("bob@example.com", "short", "Bob"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("POST /auth/signup: duplicate email → 409 Conflict")
    void signup_duplicateEmail_returns409() throws Exception {
        when(authService.signup(any(SignupRequest.class)))
                .thenThrow(new EmailAlreadyExistsException("dup@example.com"));

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("dup@example.com", "password123", "Dup"))))
                .andExpect(status().isConflict());
    }

    // ── POST /auth/login ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/login: valid credentials → 200 with tokens and tenant list")
    void login_validCredentials_returns200() throws Exception {
        TenantSummary ts = new TenantSummary(
                UUID.randomUUID(), "Acme Corp", "acme-corp",
                com.taskforge.user.entity.TenantUserRole.ADMIN);
        AuthResponse stubResponse = new AuthResponse("at", "rt", List.of(ts));
        when(authService.login(any(LoginRequest.class))).thenReturn(stubResponse);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("alice@example.com", "pass1234"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants[0].name").value("Acme Corp"))
                .andExpect(jsonPath("$.tenants[0].role").value("ADMIN"));
    }

    @Test
    @DisplayName("POST /auth/login: wrong credentials → 401 Unauthorized")
    void login_wrongCredentials_returns401() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("x@x.com", "wrong"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /auth/login: missing password → 400 Bad Request")
    void login_missingPassword_returns400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@example.com\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /auth/refresh ────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/refresh: valid refresh token → 200 with new pair")
    void refresh_validToken_returns200() throws Exception {
        RefreshResponse stub = new RefreshResponse("new-at", "new-rt");
        when(authService.refresh(any(RefreshRequest.class))).thenReturn(stub);

        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("raw-refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-at"))
                .andExpect(jsonPath("$.refreshToken").value("new-rt"));
    }

    // ── POST /auth/logout ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /auth/logout: calls service logout")
    void logout_authenticated_returns204() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isNoContent());
    }

    // ── POST /auth/switch-tenant/{tenantId} ───────────────────────────────────

    @Test
    @DisplayName("POST /auth/switch-tenant: calls service switchTenant and returns 200")
    void switchTenant_authenticated_returns200() throws Exception {
        UUID tenantId     = UUID.randomUUID();
        AuthResponse stub = new AuthResponse("at", "rt", List.of());
        when(authService.switchTenant(any(), eq(tenantId))).thenReturn(stub);

        mockMvc.perform(post("/auth/switch-tenant/" + tenantId))
                .andExpect(status().isOk());

        verify(authService).switchTenant(any(), eq(tenantId));
    }
}
