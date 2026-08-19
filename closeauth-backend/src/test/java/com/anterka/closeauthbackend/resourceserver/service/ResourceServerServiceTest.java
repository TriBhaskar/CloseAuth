package com.anterka.closeauthbackend.resourceserver.service;

import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.resourceserver.dto.ResourceServerView;
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
    private ResourceServerService service;

    @BeforeEach
    void setUp() {
        resourceServerRepository = Mockito.mock(ResourceServerRepository.class);
        scopeRepository = Mockito.mock(ResourceServerScopeRepository.class);
        ClientAuthorizedResourceServerRepository clientAuthorizationRepository =
                Mockito.mock(ClientAuthorizedResourceServerRepository.class);
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
