package com.anterka.closeauth.it.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.List;
import java.util.Map;

/**
 * A thin, reusable client for the bearer-authenticated admin API ({@code /v1/**}). Unlike {@link OAuthFlowClient} it
 * needs no PKCE / cookies / redirect handling — just {@code Authorization: Bearer <token>} calls plus a couple of
 * reusable conveniences every admin-surface stage needs:
 *
 * <ul>
 *   <li><b>Generic bearer requests</b> — {@link #get}/{@link #post}/{@link #postJson}/{@link #delete}, returning the
 *       raw REST Assured {@link Response} so the caller asserts status/body itself.</li>
 *   <li><b>RFC 7807 assertion</b> — {@link #problemCode(Response)} extracts the domain error {@code code} from a
 *       {@code application/problem+json} body, so tests can distinguish (e.g.) "blocked because last admin"
 *       ({@code tenant_role.last_admin}) from any other 409, not just check the HTTP status.</li>
 *   <li><b>Role lookup + assign</b> — {@link #tenantRoleId}/{@link #assignTenantRole}: find a tenant role by name and
 *       grant it. Nearly every RBAC-touching stage needs this "find a role by name, assign it" pattern.</li>
 *   <li><b>Fixture builders</b> — {@link #provisionActiveTenant}/{@link #registerConfidentialClient}/
 *       {@link #createActiveUser}: the admin-API setup shared across flow and RBAC journeys (extracted here so no
 *       journey re-implements them).</li>
 * </ul>
 */
public final class AdminApiClient {

    private final String appBaseUri;
    private final String contextPath;

    public AdminApiClient(String appBaseUri, String contextPath) {
        this.appBaseUri = appBaseUri;
        this.contextPath = contextPath;
    }

    // ---- generic bearer requests ------------------------------------------

    public Response get(String token, String path, Object... pathParams) {
        return bearer(token).get(path, pathParams);
    }

    public Response post(String token, String path, Object... pathParams) {
        return bearer(token).post(path, pathParams);
    }

    public Response postJson(String token, String path, Object body, Object... pathParams) {
        return bearer(token).contentType(ContentType.JSON).body(body).post(path, pathParams);
    }

    public Response putJson(String token, String path, Object body, Object... pathParams) {
        return bearer(token).contentType(ContentType.JSON).body(body).put(path, pathParams);
    }

    public Response patchJson(String token, String path, Object body, Object... pathParams) {
        return bearer(token).contentType(ContentType.JSON).body(body).patch(path, pathParams);
    }

    public Response delete(String token, String path, Object... pathParams) {
        return bearer(token).delete(path, pathParams);
    }

    /**
     * Bearer GET with query parameters (IT-8) — for the audit query API and any filtered list endpoint. Null-valued
     * entries in {@code queryParams} are omitted (so callers can pass optional filters uniformly).
     */
    public Response getQuery(String token, String path, Map<String, ?> queryParams, Object... pathParams) {
        io.restassured.specification.RequestSpecification spec = bearer(token);
        if (queryParams != null) {
            queryParams.forEach((key, value) -> {
                if (value != null) {
                    spec.queryParam(key, value);
                }
            });
        }
        return spec.get(path, pathParams);
    }

    // ---- RFC 7807 ----------------------------------------------------------

    /** The domain error {@code code} from a problem+json response (e.g. {@code tenant_role.last_admin}), or null. */
    public static String problemCode(Response response) {
        return response.jsonPath().getString("code");
    }

    // ---- role lookup + assignment -----------------------------------------

    /** Finds a tenant role's id by its name (e.g. {@code TENANT_ADMIN}), via {@code GET /v1/tenants/{id}/roles}. */
    public String tenantRoleId(String token, String tenantId, String roleName) {
        Response response = bearer(token).queryParam("size", 100).get("/v1/tenants/{tid}/roles", tenantId);
        expect(response.statusCode() == 200, "list roles failed: " + response.statusCode());
        List<Map<String, Object>> items = response.jsonPath().getList("items");
        for (Map<String, Object> role : items) {
            if (roleName.equals(role.get("name"))) {
                return String.valueOf(role.get("id"));
            }
        }
        throw new IllegalStateException("Tenant role '" + roleName + "' not found in tenant " + tenantId);
    }

    /** Grants {@code roleName} to a user; returns the role id (handy for a later revoke). Asserts a 204. */
    public String assignTenantRole(String token, String tenantId, String userId, String roleName) {
        String roleId = tenantRoleId(token, tenantId, roleName);
        Response response = post(token, "/v1/tenants/{tid}/users/{uid}/tenant-roles/{rid}", tenantId, userId, roleId);
        expect(response.statusCode() == 204,
                "assign " + roleName + " failed: " + response.statusCode() + " " + response.asString());
        return roleId;
    }

    // ---- fixture builders --------------------------------------------------

    /** Provisions a tenant (platform token) and activates it; returns the tenant id. */
    public String provisionActiveTenant(String platformToken) {
        String slug = Fixtures.slug("t");
        String tenantId = postJson(platformToken, "/v1/platform/tenants", Map.of("slug", slug, "name", "Tenant " + slug))
                .then().statusCode(201).extract().path("id");
        post(platformToken, "/v1/platform/tenants/{id}/activate", tenantId).then().statusCode(200);
        return tenantId;
    }

    /**
     * Registers a confidential (secret), PKCE, trusted client on the tenant and returns its credentials. Confidential
     * so SAS issues refresh tokens; trusted so consent is skipped; PKCE required — matching {@link OAuthFlowClient}.
     */
    public ClientCredentials registerConfidentialClient(String platformToken, String tenantId) {
        String clientId = Fixtures.clientId("c");
        return registerClient(platformToken, tenantId, clientId, "Client " + clientId, true, List.of("openid"));
    }

    /**
     * Registers a confidential PKCE client with an explicit {@code clientName}, {@code trusted} flag, and {@code scopes}
     * — the general form behind {@link #registerConfidentialClient}. IT-6 needs {@code trusted=false} (to trigger
     * consent) plus RS-prefixed scopes.
     *
     * <p><b>Predicting the auto-created RS slug:</b> the client's 1:1 Resource Server is auto-created with a slug
     * derived from {@code clientName} (the backend's {@code toSlug}). Pass a {@code clientName} that is already a clean
     * slug (lowercase, digits, hyphens — e.g. a {@code Fixtures.clientId(...)}) and the RS slug equals it, so a caller
     * can predict the {@code {slug}:scope} strings to register here and later request at {@code /authorize}.
     */
    public ClientCredentials registerClient(String platformToken, String tenantId, String clientId, String clientName,
                                            boolean trusted, List<String> scopes) {
        String secret = "secret-" + Fixtures.suffix();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("clientId", clientId);
        body.put("clientName", clientName);
        body.put("clientSecret", secret);
        body.put("grantTypes", List.of("authorization_code", "refresh_token"));
        body.put("scopes", scopes);
        body.put("redirectUris", List.of(OAuthFlowClient.REDIRECT_URI));
        body.put("requireProofKey", true);
        body.put("trusted", trusted);
        postJson(platformToken, "/v1/tenants/{tid}/clients", body, tenantId).then().statusCode(201);
        return new ClientCredentials(clientId, secret);
    }

    /** Finds a resource server's id by slug ({@code GET /v1/tenants/{tid}/resource-servers}). */
    public String resourceServerIdBySlug(String token, String tenantId, String slug) {
        Response response = bearer(token).queryParam("size", 100).get("/v1/tenants/{tid}/resource-servers", tenantId);
        expect(response.statusCode() == 200, "list resource-servers failed: " + response.statusCode());
        List<Map<String, Object>> items = response.jsonPath().getList("items");
        for (Map<String, Object> rs : items) {
            if (slug.equals(rs.get("slug"))) {
                return String.valueOf(rs.get("id"));
            }
        }
        throw new IllegalStateException("Resource server slug '" + slug + "' not found in tenant " + tenantId);
    }

    /** Creates a standalone (non-auto-created) resource server; returns its id. */
    public String createResourceServer(String platformToken, String tenantId, String slug, String name,
                                       String audienceIdentifier) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("slug", slug);
        body.put("name", name);
        body.put("audienceIdentifier", audienceIdentifier);
        Response response = postJson(platformToken, "/v1/tenants/{tid}/resource-servers", body, tenantId);
        expect(response.statusCode() == 201,
                "create resource server failed: " + response.statusCode() + " " + response.asString());
        return response.jsonPath().getString("id");
    }

    /** Creates an application (RS-scoped) role; returns its id. */
    public String createApplicationRole(String platformToken, String tenantId, String resourceServerId, String name) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", name);
        body.put("isDefault", false);
        Response response = postJson(platformToken, "/v1/tenants/{tid}/resource-servers/{rsId}/roles", body,
                tenantId, resourceServerId);
        expect(response.statusCode() == 201,
                "create application role failed: " + response.statusCode() + " " + response.asString());
        return response.jsonPath().getString("id");
    }

    /**
     * Adds a scope to a resource server's catalog ({@code POST .../resource-servers/{rsId}/scopes}); returns its id.
     * The full requestable scope string is {@code {rs_slug}:{scopeName}}.
     */
    public String addScope(String token, String tenantId, String resourceServerId, String scopeName, String description,
                           boolean requiresConsent, boolean isDefault) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("scopeName", scopeName);
        body.put("description", description);
        body.put("isDefault", isDefault);
        body.put("requiresConsent", requiresConsent);
        Response response = postJson(token, "/v1/tenants/{tid}/resource-servers/{rsId}/scopes", body,
                tenantId, resourceServerId);
        expect(response.statusCode() == 201, "add scope failed: " + response.statusCode() + " " + response.asString());
        return response.jsonPath().getString("id");
    }

    /** Admin-creates an ACTIVE password user in the tenant; returns the user id. */
    public String createActiveUser(String platformToken, String tenantId, String email, String password) {
        return postJson(platformToken, "/v1/tenants/{tid}/users",
                Map.of("email", email, "password", password, "initialStatus", "ACTIVE"), tenantId)
                .then().statusCode(201).extract().path("id");
    }

    /**
     * Sets a tenant's registration mode ({@code OPEN | EMAIL_VERIFIED | ADMIN_APPROVED | INVITE_ONLY}) via
     * {@code PUT /v1/tenants/{tid}/registration-config} (body {@code {"mode": ...}}). Asserts 200 and that the returned
     * config echoes the requested mode. Every registration-mode journey sets a mode this way.
     */
    public void setRegistrationMode(String platformToken, String tenantId, String mode) {
        Response response = putJson(platformToken, "/v1/tenants/{tid}/registration-config", Map.of("mode", mode), tenantId);
        expect(response.statusCode() == 200 && mode.equals(response.jsonPath().getString("mode")),
                "set registration mode " + mode + " failed: " + response.statusCode() + " " + response.asString());
    }

    // ---- internals ---------------------------------------------------------

    private RequestSpecification bearer(String token) {
        return RestAssured.given().baseUri(appBaseUri).basePath(contextPath).auth().oauth2(token);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("AdminApiClient: " + message);
        }
    }

    /** A registered client's id + plaintext secret (the secret is returned only at creation). */
    public record ClientCredentials(String clientId, String secret) {
    }
}
