package com.anterka.closeauthbackend.admin.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a platform-scoped admin endpoint (§7.9): the caller must be a platform admin ({@code PLATFORM_ADMIN}). A
 * tenant admin CANNOT call these — the strongest boundary. Declarative sugar over
 * {@code @PreAuthorize("@adminAuthz.hasPlatformRole('PLATFORM_ADMIN')")}, so 7b applies it consistently.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("@adminAuthz.hasPlatformRole('PLATFORM_ADMIN')")
public @interface RequiresPlatformAdmin {
}
