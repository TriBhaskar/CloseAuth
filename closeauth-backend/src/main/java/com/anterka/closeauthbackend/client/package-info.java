/**
 * OAuth2 clients registered under a tenant: web apps, SPAs, mobile apps, and M2M
 * callers that request tokens.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Tenant-owned {@code oauth2_registered_client} configuration (redirect URIs,
 *       grant types, scopes, token policies, branding).</li>
 *   <li>Auto-creation of a 1:1 Resource Server when a client is registered, keeping
 *       simple onboarding simple.</li>
 *   <li>Client branding / theme configuration.</li>
 *   <li>OIDC Dynamic Client Registration support.</li>
 * </ul>
 *
 * <p>Note: the legacy Spring Authorization Server table {@code oauth2_registered_client}
 * keeps its existing PK conventions as required by SAS; tenant ownership is added via
 * an owning association, not by changing the SAS PK.
 *
 * <p>References: Sections 7.6 (client vs. resource server), 8.4 of CLOSEAUTH_PRODUCT_VISION_V2.md.
 */
package com.anterka.closeauthbackend.client;
