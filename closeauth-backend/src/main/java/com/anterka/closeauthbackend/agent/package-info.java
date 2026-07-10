/**
 * AI-agent identity: first-class principals peer to User and Client. Phase 1 delivers
 * schema only; the feature ships in Phase 4 with MCP IdP compliance.
 *
 * <p>Responsibilities (schema reserved in Phase 1):
 * <ul>
 *   <li>{@code agents} ({@code id} UUID, {@code tenant_id}, {@code name},
 *       {@code agent_type}, {@code agent_owner_user_id}, {@code represented_user_id},
 *       {@code status}).</li>
 *   <li>{@code agent_consent_grants} recording which user granted which agent which
 *       scopes against which resource server.</li>
 *   <li>Agents authenticate through the same {@code user_identities} abstraction
 *       ({@code idp_type = AGENT_KEY}); no parallel credential system.</li>
 * </ul>
 *
 * <p>Phase 4 (NOT Stage 8): agent audit events ({@code AGENT_REGISTERED}/{@code AGENT_REVOKED}/
 * {@code AGENT_CONSENT_GRANTED}/{@code AGENT_TOKEN_EXCHANGED}) and agent schema seeding land with the
 * feature itself. Stage 8 deliberately wires NO agent audit events — the {@code agents} table is schema-only,
 * so those taxonomy values are excluded from the Stage-8 coverage enforcement until the entities exist.
 *
 * <p>References: Sections 7.10 and 7.7 (Token Exchange) of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.agent;
