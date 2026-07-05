package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.ConsentScopeView;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class ConsentScopeResolverTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID rsId = UUID.randomUUID();

    private ResourceServerRepository resourceServerRepository;
    private ResourceServerScopeRepository scopeRepository;
    private ConsentScopeResolver resolver;

    @BeforeEach
    void setUp() {
        resourceServerRepository = Mockito.mock(ResourceServerRepository.class);
        scopeRepository = Mockito.mock(ResourceServerScopeRepository.class);
        resolver = new ConsentScopeResolver(resourceServerRepository, scopeRepository);
    }

    @Test
    void platformScopesUseTheBuiltInDescriptionAndRequireConsent() {
        ConsentScopeView view = resolver.resolve(tenantId, "openid");
        assertThat(view.scope()).isEqualTo("openid");
        assertThat(view.description()).isEqualTo("Verify your identity"); // CloseAuth-controlled, not tenant-spoofable
        assertThat(view.requiresConsent()).isTrue();
    }

    @Test
    void resourceServerScopeResolvesDescriptionAndRequiresConsentFromTheCatalog() {
        stubRs("todomaster-api");
        stubScope("read", "Read your to-do items", false);

        ConsentScopeView view = resolver.resolve(tenantId, "todomaster-api:read");

        assertThat(view.description()).isEqualTo("Read your to-do items"); // NOT the raw "todomaster-api:read"
        assertThat(view.requiresConsent()).isFalse();
        assertThat(resolver.isAutoGrantable(tenantId, "todomaster-api:read")).isTrue();
    }

    @Test
    void resourceServerScopeRequiringConsentIsNotAutoGrantable() {
        stubRs("todomaster-api");
        stubScope("write", "Modify your to-do items", true);
        assertThat(resolver.isAutoGrantable(tenantId, "todomaster-api:write")).isFalse();
    }

    @Test
    void unknownScopeShowsRawAndFailsSafeToRequiringConsent() {
        when(resourceServerRepository.findBySlugInTenant("ghost", tenantId)).thenReturn(Optional.empty());
        ConsentScopeView unknownRs = resolver.resolve(tenantId, "ghost:read");
        assertThat(unknownRs.description()).isEqualTo("ghost:read");
        assertThat(unknownRs.requiresConsent()).isTrue();

        ConsentScopeView notAScope = resolver.resolve(tenantId, "mystery");
        assertThat(notAScope.requiresConsent()).isTrue(); // never silently auto-granted
    }

    private void stubRs(String slug) {
        ResourceServer rs = new ResourceServer();
        rs.setId(rsId);
        rs.setSlug(slug);
        rs.setTenantId(tenantId);
        when(resourceServerRepository.findBySlugInTenant(slug, tenantId)).thenReturn(Optional.of(rs));
    }

    private void stubScope(String bareName, String description, boolean requiresConsent) {
        ResourceServerScope scope = new ResourceServerScope();
        scope.setScopeName(bareName);
        scope.setDescription(description);
        scope.setRequiresConsent(requiresConsent);
        when(scopeRepository.findByResourceServer_IdAndScopeName(rsId, bareName)).thenReturn(Optional.of(scope));
    }
}
