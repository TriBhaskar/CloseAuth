/**
 * Resource Server: a first-class, tenant-owned entity (peer to Client) representing
 * an API that protects its endpoints with CloseAuth-issued tokens.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@code resource_servers} ({@code id}, {@code tenant_id}, {@code slug},
 *       {@code name}, {@code audience_identifier}).</li>
 *   <li>Scope catalog: {@code resource_server_scopes} ({@code scope_name},
 *       {@code description}, {@code is_default}, {@code requires_consent}).</li>
 *   <li>{@code client_authorized_resource_servers} join controlling which RS a
 *       client may request tokens for.</li>
 *   <li>Token {@code aud} = the RS {@code audience_identifier}, not the client id;
 *       tenant-RS scopes are prefixed ({@code {rs_slug}:{scope_name}}).</li>
 * </ul>
 *
 * <p>References: Section 7.6 and 8.8 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.resourceserver;
