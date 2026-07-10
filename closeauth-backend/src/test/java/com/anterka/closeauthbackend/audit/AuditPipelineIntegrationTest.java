package com.anterka.closeauthbackend.audit;

import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.repository.AuditEventQuery;
import com.anterka.closeauthbackend.audit.repository.AuditEventRepository;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.audit.service.AuditOutboxDrainWorker;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage-8 end-to-end proof of the audit pipeline + query API against live Postgres. Gated on
 * {@code -Dcloseauth.it.db.url=...} (self-skips in plain {@code mvn test}), matching every prior stage's IT convention.
 *
 * <p>Proves: (1) the outbox is <b>atomic</b> with the business action — a committed action's audit intent drains to
 * {@code audit_events}; a rolled-back action leaves NOTHING; (2) the drain worker moves outbox rows to the typed log;
 * (3) the query API's <b>tenant-scoping holds both directions</b> — a tenant admin sees only their tenant's events and
 * is 403 for another's; (4) the platform cross-tenant endpoint requires {@code PLATFORM_ADMIN}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class AuditPipelineIntegrationTest {

    private static final String BOOTSTRAP_EMAIL = "it-audit-admin@closeauth.test";
    private static final String BOOTSTRAP_PASSWORD = "It-Audit-Pw-123!";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("closeauth.issuer-url", () -> "http://localhost:9099");
        registry.add("closeauth.platform-admin.bootstrap-email", () -> BOOTSTRAP_EMAIL);
        registry.add("closeauth.platform-admin.bootstrap-password", () -> BOOTSTRAP_PASSWORD);
    }

    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Value("${server.servlet.context-path:}") String contextPath;

    @Autowired JwtEncoder jwtEncoder;
    @Autowired CloseAuthProperties properties;
    @Autowired TenantService tenantService;
    @Autowired AuditEmitter auditEmitter;
    @Autowired AuditOutboxDrainWorker drainWorker;
    @Autowired AuditEventRepository auditEventRepository;
    @Autowired PlatformTransactionManager transactionManager;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    // ---- pipeline: atomicity + drain --------------------------------------

    @Test
    void committedBusinessActionDrainsToAuditEvents() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("audit-" + rnd(), "Audit Co"));
        tenantService.activateTenant(tenant.id());
        tenantService.suspendTenant(tenant.id()); // emits TENANT_SUSPENDED inside the business transaction

        drainWorker.drainOnce();

        long suspended = auditEventRepository.count(typeQuery(tenant.id(), AuditEventType.TENANT_SUSPENDED));
        assertThat(suspended).isGreaterThanOrEqualTo(1); // business action + audit intent committed together
    }

    @Test
    void rolledBackActionLeavesNoAuditEvent() {
        UUID orphanTenant = UUID.randomUUID(); // a tenant id used by nothing else — a clean probe
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        try {
            tx.executeWithoutResult(status -> {
                auditEmitter.emit(AuditEvents.tenantSuspended(orphanTenant));
                throw new IllegalStateException("force rollback after emit");
            });
        } catch (IllegalStateException expected) {
            // the business tx rolled back; the BEFORE_COMMIT outbox write must never have fired
        }

        drainWorker.drainOnce();
        long events = auditEventRepository.count(typeQuery(orphanTenant, AuditEventType.TENANT_SUSPENDED));
        assertThat(events).isZero(); // nothing recorded — atomic with the rolled-back action
    }

    // ---- query API: tenant isolation (both directions) --------------------

    @Test
    void tenantAuditQueryIsIsolatedBothDirections() throws Exception {
        TenantView a = suspendedTenant();
        TenantView b = suspendedTenant();
        drainWorker.drainOnce();

        String tokenForA = forgeTenantAdminToken(a.id().toString());

        // A's admin sees A's events...
        HttpResponse<String> ownView = get("/v1/tenants/" + a.id() + "/audit-events?event_type=TENANT_SUSPENDED",
                tokenForA);
        assertThat(ownView.statusCode()).isEqualTo(200);
        JsonNode items = mapper.readTree(ownView.body()).get("items");
        assertThat(items).isNotEmpty();
        items.forEach(item -> assertThat(item.get("tenantId").asText()).isEqualTo(a.id().toString()));

        // ...and is FORBIDDEN for B's events (the cross-tenant gate).
        HttpResponse<String> crossView = get("/v1/tenants/" + b.id() + "/audit-events", tokenForA);
        assertThat(crossView.statusCode()).isEqualTo(403);
    }

    // ---- platform cross-tenant endpoint -----------------------------------

    @Test
    void platformAuditEndpointRequiresPlatformAdmin() throws Exception {
        TenantView b = suspendedTenant();
        drainWorker.drainOnce();

        // A tenant admin is 403 on the platform endpoint.
        String tenantAdmin = forgeTenantAdminToken(UUID.randomUUID().toString());
        assertThat(get("/v1/platform/audit-events", tenantAdmin).statusCode()).isEqualTo(403);

        // A platform admin may read across tenants, filtered to a specific one.
        String platformToken = platformToken();
        HttpResponse<String> resp = get("/v1/platform/audit-events?tenant_id=" + b.id()
                + "&event_type=TENANT_SUSPENDED", platformToken);
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode items = mapper.readTree(resp.body()).get("items");
        assertThat(items).isNotEmpty();
        items.forEach(item -> assertThat(item.get("tenantId").asText()).isEqualTo(b.id().toString()));
    }

    // ---- helpers ----------------------------------------------------------

    private TenantView suspendedTenant() {
        TenantView tenant = tenantService.provisionTenant(new ProvisionTenantCommand("audit-" + rnd(), "Audit Co"));
        tenantService.activateTenant(tenant.id());
        tenantService.suspendTenant(tenant.id());
        return tenant;
    }

    private AuditEventQuery typeQuery(UUID tenantId, AuditEventType type) {
        return new AuditEventQuery(tenantId, type, null, null, null, null, null, 0, 100);
    }

    private String platformToken() throws Exception {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/platform/auth/token"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\":\"" + BOOTSTRAP_EMAIL + "\",\"password\":\"" + BOOTSTRAP_PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return mapper.readTree(resp.body()).get("access_token").asText();
    }

    private String forgeTenantAdminToken(String tenantId) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuerUrl())
                .subject(UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .claim("roles", List.of())
                .claim("tenant_id", tenantId)
                .claim("tenant_roles", List.of("TENANT_ADMIN"))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String rnd() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String base() {
        return "http://localhost:" + port + contextPath;
    }
}
