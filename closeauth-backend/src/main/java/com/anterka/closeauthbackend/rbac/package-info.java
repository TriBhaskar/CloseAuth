/**
 * Three-tier RBAC: Platform, Tenant, and Application roles, where permissions are
 * Resource Server scopes rather than a parallel permission catalog.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@code platform_roles} + {@code user_platform_roles} (staff only, usually
 *       zero rows per user).</li>
 *   <li>{@code tenant_roles} + {@code user_tenant_roles} ({@code (tenant_id, user_id,
 *       role_id)}) with predefined defaults plus tenant-custom roles.</li>
 *   <li>{@code application_roles} linked to {@code resource_server_id} (refactored
 *       away from {@code client_id}) + {@code user_application_roles}.</li>
 *   <li>Role templates / starter packs pre-populated on tenant creation.</li>
 *   <li>Deletion of the legacy {@code GlobalRoleEnum}: {@code SUPER_ADMIN ->
 *       PLATFORM_ADMIN}, {@code CLIENT_ADMIN -> TENANT_ADMIN}, {@code END_USER ->}
 *       (no equivalent).</li>
 * </ul>
 *
 * <p>References: Section 7.9 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.rbac;
