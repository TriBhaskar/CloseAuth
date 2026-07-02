/**
 * Tenant as a first-class entity: the top-level customer entity that owns clients,
 * users, resource servers, roles, branding, and audit scope.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Tenant lifecycle: {@code PROVISIONING -> ACTIVE -> SUSPENDED -> DELETED}.</li>
 *   <li>Immutable {@code tenant_id} (UUID) primary key and mutable human-readable
 *       {@code slug}; slug renames must not affect issued tokens.</li>
 *   <li>Multi-admin model ({@code TENANT_ADMIN}) with the invariant that a tenant
 *       can never be left with zero admins.</li>
 *   <li>Ownership graph root: every tenant-owned entity carries {@code tenant_id}.</li>
 * </ul>
 *
 * <p>References: Sections 7.1 and 11 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.tenant;
