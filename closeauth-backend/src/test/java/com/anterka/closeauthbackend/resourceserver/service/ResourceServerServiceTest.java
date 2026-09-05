package com.anterka.closeauthbackend.resourceserver.service;

import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.resourceserver.dto.ResourceServerView;
import com.anterka.closeauthbackend.resourceserver.entity.ClientAuthorizedResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.repository.ClientAuthorizedResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeCountProjection;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * FE-4b: {@link ResourceServerService#listResourceServers}'s new {@code scopeCount} decoration (spec §6.4.4's
 * list "Scope count" column). No prior unit-test coverage existed for this service; this file covers only the
 * new decoration, not a full retrofit of the class.
 */
class ResourceServerServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private ResourceServerRepository resourceServerRepository;
    private ResourceServerScopeRepository scopeRepository;
    private ClientAuthorizedResourceServerRepository clientAuthorizationRepository;
    private ResourceServerService service;

    @BeforeEach
    void setUp() {
        resourceServerRepository = Mockito.mock(ResourceServerRepository.class);
        scopeRepository = Mockito.mock(ResourceServerScopeRepository.class);
        clientAuthorizationRepository = Mockito.mock(ClientAuthorizedResourceServerRepository.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        CommandValidator commandValidator = Mockito.mock(CommandValidator.class);
        CloseAuthProperties properties = new CloseAuthProperties();
        AuditEmitter auditEmitter = Mockito.mock(AuditEmitter.class);
        service = new ResourceServerService(resourceServerRepository, scopeRepository, clientAuthorizationRepository,
                tenantService, commandValidator, properties, auditEmitter);
    }

    @Test
    void listResourceServersDecoratesScopeCountFromOneBulkQuery() {
        ResourceServer withScopes = resourceServer();
        ResourceServer withoutScopes = resourceServer();
        when(resourceServerRepository.findByTenantId(tenantId)).thenReturn(List.of(withScopes, withoutScopes));
        when(scopeRepository.countScopesByTenant(tenantId))
                .thenReturn(List.of(projection(withScopes.getId(), 5L)));

        List<ResourceServerView> result = service.listResourceServers(ctx);

        assertThat(result).extracting(ResourceServerView::id, ResourceServerView::scopeCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(withScopes.getId(), 5L),
                        org.assertj.core.groups.Tuple.tuple(withoutScopes.getId(), 0L));
        // One call for the whole tenant, not one per resource server.
        Mockito.verify(scopeRepository, Mockito.times(1)).countScopesByTenant(tenantId);
    }

    // ---- deleteAutoCreatedForClient: the reachable path ResourceServerDeletionNotAllowedException points to ----

    @Test
    void deleteAutoCreatedForClientDeletesTheAutoCreatedRs() {
        ResourceServer autoRs = resourceServer();
        autoRs.setAutoCreated(true);
        ClientAuthorizedResourceServer link = link("client-1", autoRs.getId());
        when(clientAuthorizationRepository.findByClientRegisteredId("client-1")).thenReturn(List.of(link));
        when(resourceServerRepository.findById(autoRs.getId())).thenReturn(java.util.Optional.of(autoRs));

        service.deleteAutoCreatedForClient(ctx, "client-1");

        Mockito.verify(resourceServerRepository).delete(autoRs);
    }

    @Test
    void deleteAutoCreatedForClientIsANoOpWhenNoLinkExists() {
        // The platform-managed admin-console client (and any client predating the auto-create callback) has
        // no auto-created RS at all — this must not throw.
        when(clientAuthorizationRepository.findByClientRegisteredId("admin-console-acme")).thenReturn(List.of());

        service.deleteAutoCreatedForClient(ctx, "admin-console-acme");

        Mockito.verify(resourceServerRepository, Mockito.never()).delete(Mockito.any());
    }

    @Test
    void deleteAutoCreatedForClientNeverTouchesAStandaloneNonAutoCreatedRs() {
        // A client explicitly authorized against a standalone RS (via authorizeClientForResourceServer) must
        // not have that RS swept up by a client delete — only the 1:1 auto-created one is in scope.
        ResourceServer standalone = resourceServer();
        standalone.setAutoCreated(false);
        ClientAuthorizedResourceServer link = link("client-1", standalone.getId());
        when(clientAuthorizationRepository.findByClientRegisteredId("client-1")).thenReturn(List.of(link));
        when(resourceServerRepository.findById(standalone.getId())).thenReturn(java.util.Optional.of(standalone));

        service.deleteAutoCreatedForClient(ctx, "client-1");

        Mockito.verify(resourceServerRepository, Mockito.never()).delete(Mockito.any());
    }

    @Test
    void deleteAutoCreatedForClientNeverTouchesAnotherTenantsRs() {
        // Defense in depth: this should never happen (a client belongs to exactly one tenant), but a link
        // resolving to a different tenant's RS must still be filtered out, not trusted.
        ResourceServer othersRs = resourceServer();
        othersRs.setAutoCreated(true);
        othersRs.setTenantId(UUID.randomUUID());
        ClientAuthorizedResourceServer link = link("client-1", othersRs.getId());
        when(clientAuthorizationRepository.findByClientRegisteredId("client-1")).thenReturn(List.of(link));
        when(resourceServerRepository.findById(othersRs.getId())).thenReturn(java.util.Optional.of(othersRs));

        service.deleteAutoCreatedForClient(ctx, "client-1");

        Mockito.verify(resourceServerRepository, Mockito.never()).delete(Mockito.any());
    }

    private ClientAuthorizedResourceServer link(String clientRegisteredId, UUID resourceServerId) {
        ClientAuthorizedResourceServer link = new ClientAuthorizedResourceServer();
        link.setId(UUID.randomUUID());
        link.setClientRegisteredId(clientRegisteredId);
        link.setResourceServerId(resourceServerId);
        return link;
    }

    private ResourceServer resourceServer() {
        ResourceServer rs = new ResourceServer();
        rs.setId(UUID.randomUUID());
        rs.setTenantId(tenantId);
        rs.setSlug("rs-" + UUID.randomUUID());
        rs.setName("RS");
        rs.setAudienceIdentifier("https://example.test/rs");
        rs.setCreatedAt(Instant.now());
        return rs;
    }

    private ResourceServerScopeCountProjection projection(UUID resourceServerId, long count) {
        return new ResourceServerScopeCountProjection() {
            @Override
            public UUID getResourceServerId() {
                return resourceServerId;
            }

            @Override
            public long getScopeCount() {
                return count;
            }
        };
    }
}
