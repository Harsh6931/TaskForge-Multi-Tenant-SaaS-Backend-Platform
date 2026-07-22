package com.taskforge.auth.dto;

import com.taskforge.user.entity.TenantUserRole;

import java.util.UUID;

/**
 * TenantSummary — compact representation of a tenant the user belongs to.
 *
 * <p>Returned inside {@link AuthResponse#tenants()} on login and switch-tenant.
 * The frontend uses this list to render the workspace switcher (like Slack's sidebar).
 *
 * <p>Per API contract ({@code POST /auth/login → { tenants[] }}):
 * each entry tells the frontend the tenant's name, slug (for URL routing),
 * and the user's role in that workspace (so the UI can show/hide admin menus
 * before the first API call).
 */
public record TenantSummary(
        UUID tenantId,
        String name,
        String slug,
        TenantUserRole role
) {}
