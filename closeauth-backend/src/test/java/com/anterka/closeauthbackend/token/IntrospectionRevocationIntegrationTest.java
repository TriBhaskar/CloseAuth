package com.anterka.closeauthbackend.token;

import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: {@code /oauth2/introspect} consults the Redis revocation list. Verifies that revoking a tenant's tokens
 * flips a structurally-valid, unexpired token to {@code active:false} with no claim leakage, and that a different
 * tenant is unaffected. Gated on {@code -Dcloseauth.it.db.url=...} (self-skips in plain {@code mvn test}); run against
 * a live Postgres + Redis (see 4a's integration test for setup).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class IntrospectionRevocationIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("closeauth.issuer-url", () -> "http://localhost:9088");
    }

    @LocalServerPort int port;
    @Autowired TenantService tenantService;
    @Autowired ClientRegistrationService clientRegistrationService;
    @Autowired TokenRevocationService tokenRevocationService;
    @Autowired TestRestTemplate rest;

    private static final String SECRET = "test-secret-value";
    private static final String SCOPE = "m2m-client:read";

    private String base() {
        return "http://localhost:" + port + "/closeauth";
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** @return the tenant id, having registered a confidential client under it. */
    private UUID tenantWithClient(String clientId) {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("acme-" + rnd(), "Acme"));
        tenantService.activateTenant(tenant.id());
        clientRegistrationService.registerClient(TenantContext.of(tenant.id()), new RegisterClientCommand(
                clientId, "M2M Client", SECRET, List.of("client_credentials"), List.of(SCOPE), null, false));
        return tenant.id();
    }

    private String obtainToken(String clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, SECRET);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", SCOPE);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>)
                rest.postForEntity(base() + "/oauth2/token", new HttpEntity<>(form, headers), Map.class);
        return (String) response.getBody().get("access_token");
    }

    private Map<String, Object> introspect(String clientId, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, SECRET);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response = (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>)
                rest.postForEntity(base() + "/oauth2/introspect", new HttpEntity<>(form, headers), Map.class);
        return response.getBody();
    }

    @Test
    void introspectionReflectsTenantRevocationWithoutLeakingClaims() {
        String clientA = "m2m-" + rnd();
        UUID tenantA = tenantWithClient(clientA);
        String tokenA = obtainToken(clientA);

        // Before revocation: active, with claims.
        Map<String, Object> before = introspect(clientA, tokenA);
        assertThat(before.get("active")).isEqualTo(Boolean.TRUE);
        assertThat(before.get("tenant_id")).isEqualTo(tenantA.toString());

        // A different tenant's token — must stay active after we revoke tenant A.
        String clientB = "m2m-" + rnd();
        tenantWithClient(clientB);
        String tokenB = obtainToken(clientB);

        // Revoke ALL of tenant A's tokens.
        tokenRevocationService.revokeAllTenantTokens(tenantA);

        // Tenant A's token now reports inactive with NO other claims (RFC 7662 — no leakage).
        Map<String, Object> after = introspect(clientA, tokenA);
        assertThat(after.get("active")).isEqualTo(Boolean.FALSE);
        assertThat(after).doesNotContainKeys("tenant_id", "client_id", "sub", "scope", "iat", "exp");

        // Tenant B is unaffected (isolation).
        assertThat(introspect(clientB, tokenB).get("active")).isEqualTo(Boolean.TRUE);
    }
}
