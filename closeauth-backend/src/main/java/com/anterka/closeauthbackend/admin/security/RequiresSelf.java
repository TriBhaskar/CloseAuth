package com.anterka.closeauthbackend.admin.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a self-service endpoint (§7.8, {@code /v1/me/**}): the caller is authenticated and operates ONLY on their own
 * {@code sub}'s resources — NOT platform/tenant-admin gated. This is a deliberate, intentionally-scoped exception to
 * admin gating (not "public"): the enumeration test recognizes it as gated-by-self.
 *
 * <p>The annotation enforces only <b>authentication</b> ({@code isAuthenticated()}); the actual self-scoping — that the
 * token's {@code sub} owns the resource being touched — is enforced inside the controller/service (e.g. a session must
 * belong to the caller's {@code sub}), because ownership generally needs a data lookup a static SpEL can't do. A caller
 * must never reach another principal's resource through {@code /v1/me/**}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("isAuthenticated()")
public @interface RequiresSelf {
}
