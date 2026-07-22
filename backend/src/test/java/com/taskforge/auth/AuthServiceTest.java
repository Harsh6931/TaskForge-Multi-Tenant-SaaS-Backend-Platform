package com.taskforge.auth;

import com.taskforge.auth.dto.*;
import com.taskforge.auth.entity.RefreshToken;
import com.taskforge.auth.repository.RefreshTokenRepository;
import com.taskforge.auth.util.TokenHashUtil;
import com.taskforge.common.exception.EmailAlreadyExistsException;
import com.taskforge.common.exception.InvalidCredentialsException;
import com.taskforge.common.exception.InvalidTokenException;
import com.taskforge.common.exception.TenantAccessDeniedException;
import com.taskforge.config.JwtProperties;
import com.taskforge.tenant.entity.Tenant;
import com.taskforge.tenant.repository.TenantRepository;
import com.taskforge.user.entity.TenantUser;
import com.taskforge.user.entity.TenantUserRole;
import com.taskforge.user.entity.User;
import com.taskforge.user.repository.TenantUserRepository;
import com.taskforge.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthServiceTest — unit test for {@link AuthService}.
 *
 * <p><b>No Spring context.</b> All dependencies are Mockito mocks.
 * JwtProperties + JwtService + PasswordEncoder are constructed directly
 * (they are pure POJOs or have no external dependencies).
 *
 * <p>This test verifies every branch of AuthService business logic:
 * happy paths, duplicate email, bad credentials, token revocation/expiry, etc.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — business logic unit tests")
class AuthServiceTest {

    // ── Mocks ─────────────────────────────────────────────────────────────────
    @Mock private UserRepository         userRepository;
    @Mock private TenantUserRepository   tenantUserRepository;
    @Mock private TenantRepository       tenantRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;

    // ── Real instances (stateless, no Spring required) ────────────────────────
    private JwtService      jwtService;
    private PasswordEncoder passwordEncoder;
    private JwtProperties   jwtProperties;

    // ── System under test ─────────────────────────────────────────────────────
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret("test-secret-key-must-be-at-least-32-bytes-long-here!");
        jwtProperties.setAccessTokenExpiryMs(900_000L);
        jwtProperties.setRefreshTokenExpiryMs(604_800_000L);

        jwtService      = new JwtService(jwtProperties);
        passwordEncoder = new BCryptPasswordEncoder();

        authService = new AuthService(
                userRepository, tenantUserRepository, tenantRepository,
                refreshTokenRepository, jwtService, passwordEncoder, jwtProperties);
    }

    // ── signup ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("signup: happy path — saves user, returns tokens, empty tenant list")
    void signup_happyPath() {
        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);

        User savedUser = savedUserWith(UUID.randomUUID(), "bob@example.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.signup(
                new SignupRequest("bob@example.com", "password123", "Bob"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tenants()).isEmpty();
        verify(userRepository).save(any(User.class));
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("signup: duplicate email → throws EmailAlreadyExistsException (409)")
    void signup_duplicateEmail_throws() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("dup@example.com", "pass1234", "Dup")))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("signup: password is BCrypt-encoded before saving (never stored as plaintext)")
    void signup_passwordIsEncoded() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        User savedUser = savedUserWith(UUID.randomUUID(), "enc@example.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        authService.signup(new SignupRequest("enc@example.com", "rawpassword", "Enc"));

        verify(userRepository).save(userCaptor.capture());
        String storedHash = userCaptor.getValue().getPasswordHash();

        assertThat(storedHash).isNotEqualTo("rawpassword");
        assertThat(passwordEncoder.matches("rawpassword", storedHash)).isTrue();
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("login: happy path — returns access token, refresh token, and tenant list")
    void login_happyPath() {
        String rawPassword = "pass1234";
        String hash        = passwordEncoder.encode(rawPassword);
        User user          = savedUserWith(UUID.randomUUID(), "alice@example.com");
        user.setPasswordHash(hash);

        Tenant tenant       = tenantWith("Acme");
        TenantUser membership = tenantUserWith(user, tenant, TenantUserRole.ADMIN);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(tenantUserRepository.findAllByUserId(user.getId())).thenReturn(List.of(membership));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.login(new LoginRequest("alice@example.com", rawPassword));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.tenants()).hasSize(1);
        assertThat(response.tenants().get(0).role()).isEqualTo(TenantUserRole.ADMIN);
    }

    @Test
    @DisplayName("login: unknown email → throws InvalidCredentialsException (401)")
    void login_unknownEmail_throws() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("x@x.com", "pass")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("login: wrong password → throws InvalidCredentialsException (401)")
    void login_wrongPassword_throws() {
        User user = savedUserWith(UUID.randomUUID(), "alice@example.com");
        user.setPasswordHash(passwordEncoder.encode("correctpassword"));
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        // No tenantUserRepository stub — login throws before reaching that call

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice@example.com", "wrongpassword")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh: valid token → rotates token, returns new pair")
    void refresh_validToken_rotates() {
        User user      = savedUserWith(UUID.randomUUID(), "bob@example.com");
        String rawToken = UUID.randomUUID().toString();
        String hash     = TokenHashUtil.sha256(rawToken);

        RefreshToken stored = activeRefreshToken(user, hash);

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));
        when(tenantUserRepository.findAllByUserId(user.getId())).thenReturn(List.of());
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefreshResponse response = authService.refresh(new RefreshRequest(rawToken));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        // Old token should be revoked
        assertThat(stored.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("refresh: unknown token → throws InvalidTokenException (401)")
    void refresh_unknownToken_throws() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("unknown-raw-token")))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("refresh: revoked token → throws InvalidTokenException (401)")
    void refresh_revokedToken_throws() {
        User user      = savedUserWith(UUID.randomUUID(), "bob@example.com");
        String rawToken = UUID.randomUUID().toString();
        String hash     = TokenHashUtil.sha256(rawToken);

        RefreshToken revoked = activeRefreshToken(user, hash);
        revoked.setRevokedAt(Instant.now().minusSeconds(60));

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("refresh: expired token → throws InvalidTokenException (401)")
    void refresh_expiredToken_throws() {
        User user      = savedUserWith(UUID.randomUUID(), "bob@example.com");
        String rawToken = UUID.randomUUID().toString();
        String hash     = TokenHashUtil.sha256(rawToken);

        RefreshToken expired = RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash(hash)
                .expiresAt(Instant.now().minusSeconds(3600))  // 1 hour ago
                .build();

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout: calls revokeAllActiveByUserId with the userId and a current timestamp")
    void logout_revokesAllTokens() {
        UUID userId = UUID.randomUUID();
        authService.logout(userId);

        verify(refreshTokenRepository).revokeAllActiveByUserId(eq(userId), any(Instant.class));
    }

    // ── switchTenant ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("switchTenant: valid membership → returns tenant-scoped token pair")
    void switchTenant_validMembership_returnsTokens() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        User user       = savedUserWith(userId, "alice@example.com");
        Tenant tenant   = tenantWith("Acme");
        TenantUser tu   = tenantUserWith(user, tenant, TenantUserRole.MANAGER);

        when(tenantUserRepository.findByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Optional.of(tu));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(tenantUserRepository.findAllByUserId(userId)).thenReturn(List.of(tu));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.switchTenant(userId, tenantId);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.tenants()).hasSize(1);
        assertThat(response.tenants().get(0).role()).isEqualTo(TenantUserRole.MANAGER);
    }

    @Test
    @DisplayName("switchTenant: user not a member → throws TenantAccessDeniedException (403)")
    void switchTenant_notMember_throws() {
        UUID userId   = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(tenantUserRepository.findByTenantIdAndUserId(tenantId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.switchTenant(userId, tenantId))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private User savedUserWith(UUID id, String email) {
        User user = User.builder()
                .email(email)
                .passwordHash("hashed")
                .fullName("Test User")
                .build();
        // Reflectively set the id via the inherited BaseEntity setter
        user.setId(id);
        return user;
    }

    private Tenant tenantWith(String name) {
        Tenant t = Tenant.builder().name(name).slug(name.toLowerCase()).build();
        t.setId(UUID.randomUUID());
        return t;
    }

    private TenantUser tenantUserWith(User user, Tenant tenant, TenantUserRole role) {
        return TenantUser.builder()
                .user(user)
                .tenant(tenant)
                .role(role)
                .build();
    }

    private RefreshToken activeRefreshToken(User user, String hash) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tokenHash(hash)
                .expiresAt(Instant.now().plusSeconds(604_800))
                .build();
    }
}
