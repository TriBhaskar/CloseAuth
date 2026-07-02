/**
 * Audit as a Tier-1 product: a structured, immutable, queryable, exportable event
 * log — not an operational by-product.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>A defined event taxonomy of canonical, typed event types (authentication,
 *       identity, tenant, authorization, client, resource server, agent, admin).</li>
 *   <li>Append-only storage: the application audit-write role has INSERT-only
 *       privileges; UPDATE/DELETE are denied to application code.</li>
 *   <li>Outbox pattern: events written to a local outbox within the auth-flow
 *       transaction, drained asynchronously to the main audit store.</li>
 *   <li>Delegation-aware shape from Phase 1: {@code subject_user_id},
 *       {@code actor_user_id}/{@code actor_client_id}, and reserved
 *       {@code actor_agent_id}/{@code granted_consent_id} for full chain traceability.</li>
 *   <li>Tenant-scoped query API; cross-tenant queries only via a separate
 *       platform-admin endpoint.</li>
 * </ul>
 *
 * <p>References: Section 7.11 and 8.10 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.audit;
