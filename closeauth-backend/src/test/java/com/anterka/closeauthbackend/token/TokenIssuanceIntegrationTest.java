package com.anterka.closeauthbackend.token;

import com.anterka.closeauthbackend.client.dto.ClientCreatedView;
import com.anterka.closeauthbackend.client.dto.ClientView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke for the 4a token layer against a real Postgres + Redis. Exercises the runtime-only pieces the
 * unit tests can't: the tenant-aware {@code oauth2_registered_client} INSERT (13-col assertion) and
 * {@code oauth2_authorization} INSERT (33-col assertion), the {@code autoCreateForClient} trigger, the token
 * customizer over the real client-credentials flow, JWKS, OIDC discovery, issuer, and the 5-minute TTL.
 *
 * <p><b>Runs only when the {@code closeauth.it.db.url} system property is set</b> (self-skips in plain
 * {@code mvn test}). Point it at an already-running Postgres + Redis, e.g.:
 * <pre>
 *   mvn test -Dtest=TokenIssuanceIntegrationTest \
 *       -Dcloseauth.it.db.url=jdbc:postgresql://localhost:55432/closeauth \
 *       -Dcloseauth.it.db.username=closeauth -Dcloseauth.it.db.password=closeauth \
 *       -Dcloseauth.it.redis.host=localhost -Dcloseauth.it.redis.port=63790
 * </pre>
 * (Testcontainers-managed containers were the first choice but this environment's Docker Desktop API isn't
 * reachable by docker-java; externally-provided containers give the same real coverage.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class TokenIssuanceIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("closeauth.issuer-url", () -> "http://localhost:9088");
    }

    @LocalServerPort
    int port;

    @Autowired
    TenantService tenantService;
    @Autowired
    ClientRegistrationService clientRegistrationService;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private String base() {
        return "http://localhost:" + port + "/closeauth";
    }

    @Test
    void clientCredentialsTokenCarriesTenantAudienceAndTtl() throws Exception {
        // --- arrange: an active tenant with a registered confidential client (triggers RS auto-creation) ---
        String slug = "acme-" + UUID.randomUUID().toString().substring(0, 8);
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand(slug, "Acme"));
        tenantService.activateTenant(tenant.id());
        TenantContext ctx = TenantContext.of(tenant.id());

        String clientId = "m2m-" + UUID.randomUUID().toString().substring(0, 8);
        // The auto-created RS's slug is derived from the client name ("M2M Client" -> "m2m-client"); the client
        // requests that RS's scope in prefixed form so aud is inferred from the scope prefix.
        String rsScope = "m2m-client:read";
        ClientCreatedView created = clientRegistrationService.registerClient(ctx, new RegisterClientCommand(
                clientId, "M2M Client", false, List.of("client_credentials"), List.of(rsScope), null, false, true));
        ClientView client = created.client();
        String secret = created.clientSecret();

        // the tenant-aware INSERT populated the tenant_id column (13-col path)
        String storedTenant = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM oauth2_registered_client WHERE client_id = ?", String.class, clientId);
        assertThat(storedTenant).isEqualTo(tenant.id().toString());

        // autoCreateForClient created the 1:1 Resource Server, linked to the client
        String rsAudience = jdbcTemplate.queryForObject(
                "SELECT rs.audience_identifier FROM resource_servers rs "
                        + "JOIN client_authorized_resource_servers link ON link.resource_server_id = rs.id "
                        + "WHERE link.client_id = ?", String.class, client.id());
        assertThat(rsAudience).startsWith("https://" + slug + ".rs.closeauth.io/");

        // --- act: obtain a client-credentials token over HTTP ---
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, secret);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", rsScope);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> tokenResponse = (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>)
                rest.postForEntity(base() + "/oauth2/token", new HttpEntity<>(form, headers), Map.class);

        assertThat(tokenResponse.getStatusCode().is2xxSuccessful()).isTrue();
        String accessToken = (String) tokenResponse.getBody().get("access_token");
        assertThat(accessToken).isNotBlank();

        // --- assert: the JWT carries the §12 claims ---
        JWTClaimsSet claims = SignedJWT.parse(accessToken).getJWTClaimsSet();
        assertThat(claims.getStringClaim("tenant_id")).isEqualTo(tenant.id().toString());
        assertThat(claims.getStringClaim("client_id")).isEqualTo(clientId);
        assertThat(claims.getAudience()).containsExactly(rsAudience);      // aud = RS audience URI, not client_id
        assertThat(claims.getAudience()).doesNotContain(clientId);
        assertThat(claims.getIssuer()).isEqualTo("http://localhost:9088");
        long ttlSeconds = (claims.getExpirationTime().getTime() - claims.getIssueTime().getTime()) / 1000;
        assertThat(ttlSeconds).isEqualTo(300);                            // 5-minute access token (§7.3)

        // the tenant-aware authorization INSERT populated tenant_id (33-col path)
        Integer authWithTenant = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth2_authorization WHERE tenant_id = CAST(? AS uuid)",
                Integer.class, tenant.id().toString());
        assertThat(authWithTenant).isGreaterThanOrEqualTo(1);

        // --- assert: JWKS + OIDC discovery resolve ---
        ResponseEntity<String> jwks = rest.exchange(base() + "/oauth2/jwks", HttpMethod.GET, null, String.class);
        assertThat(jwks.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jwks.getBody()).contains("\"kid\"").contains("RSA");

        ResponseEntity<String> discovery = rest.exchange(
                base() + "/.well-known/openid-configuration", HttpMethod.GET, null, String.class);
        assertThat(discovery.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(discovery.getBody()).contains("http://localhost:9088").contains("jwks_uri");
    }
}
