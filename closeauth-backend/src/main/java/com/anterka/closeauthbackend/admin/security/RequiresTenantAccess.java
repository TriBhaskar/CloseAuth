package com.anterka.closeauthbackend.admin.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a tenant-scoped admin endpoint (§7.9): the caller must be authorized for the tenant in the path — either a
 * platform admin (any tenant) or a {@code TENANT_ADMIN} whose token tenant matches. Declarative sugar over
 * {@code @PreAuthorize("@adminAuthz.hasTenantAccess(#tenantId)")}.
 *
 * <p><b>Convention:</b> the annotated method MUST have a parameter named {@code tenantId} (the path variable) — the
 * SpEL {@code #tenantId} binds to it. This is the cross-tenant admin guard (tenant admin of A denied for B).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("@adminAuthz.hasTenantAccess(#tenantId)")
public @interface RequiresTenantAccess {
}
