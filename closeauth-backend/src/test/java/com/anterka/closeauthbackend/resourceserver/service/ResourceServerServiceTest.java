package com.anterka.closeauthbackend.resourceserver.service;

import com.anterka.closeauthbackend.common.exception.AudienceIdentifierConflictException;
import com.anterka.closeauthbackend.common.exception.ResourceServerNotFoundException;
import com.anterka.closeauthbackend.common.exception.ResourceServerSlugConflictException;
import com.anterka.closeauthbackend.common.exception.ScopeNameConflictException;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.resourceserver.dto.AddScopeCommand;
import com.anterka.closeauthbackend.resourceserver.dto.CreateResourceServerCommand;
import com.anterka.closeauthbackend.resourceserver.dto.ResourceServerView;
import com.anterka.closeauthbackend.resourceserver.dto.ScopeView;
import com.anterka.closeauthbackend.resourceserver.dto.UpdateResourceServerCommand;
import com.anterka.closeauthbackend.resourceserver.entity.ClientAuthorizedResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import com.anterka.closeauthbackend.resourceserver.repository.ClientAuthorizedResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceServerServiceTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private final TenantContext ctxA = TenantContext.of(TENANT_A);

    private ResourceServerRepository rsRepo;
    private ResourceServerScopeRepository scopeRepo;
    private ClientAuthorizedResourceServerRepository clientAuthRepo;
    private ResourceServerService service;

    @BeforeEach
    void setUp() {
        rsRepo = Mockito.mock(ResourceServerRepository.class);
        scopeRepo = Mockito.mock(ResourceServerScopeRepository.class);
        clientAuthRepo = Mockito.mock(ClientAuthorizedResourceServerRepository.class);
        TenantService tenantService = Mockito.mock(TenantService.class);
        CommandValidator commandValidator =
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());
        // Default audienceHostBase = "rs.closeauth.io".
        CloseAuthProperties properties = new CloseAuthProperties();
        service = new ResourceServerService(
                rsRepo, scopeRepo, clientAuthRepo, tenantService, commandValidator, properties);

        // requireActiveTenant returns a live tenant view (autoCreate reads its slug).
        when(tenantService.requireActiveTenant(any())).thenReturn(
                new TenantView(TENANT_A, "acme", "Acme", TenantStatus.ACTIVE, Instant.now(), null, null));
        when(rsRepo.save(any(ResourceServer.class))).thenAnswer(inv -> {
            ResourceServer r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
        when(scopeRepo.save(any(ResourceServerScope.class))).thenAnswer(inv -> inv.getArgument(0));
        when(clientAuthRepo.save(any(ClientAuthorizedResourceServer.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ResourceServer resourceServer(String slug, String name, String audience, boolean autoCreated) {
        ResourceServer r = new ResourceServer();
        r.setId(UUID.randomUUID());
        r.setTenantId(TENANT_A);
        r.setSlug(slug);
        r.setName(name);
        r.setAudienceIdentifier(audience);
        r.setAutoCreated(autoCreated);
        return r;
    }

    // ---- create: uniqueness -----------------------------------------------

    @Test
    void createRejectsDuplicateSlugWithinTenant() {
        when(rsRepo.findBySlugInTenant("todos", TENANT_A))
                .thenReturn(Optional.of(resourceServer("todos", "x", "acme:todos", false)));

        assertThatThrownBy(() -> service.createResourceServer(ctxA,
                new CreateResourceServerCommand("todos", "Todos API", "acme:todos")))
                .isInstanceOf(ResourceServerSlugConflictException.class);
        verify(rsRepo, never()).save(any());
    }

    @Test
    void createRejectsDuplicateAudienceGlobally() {
        when(rsRepo.findBySlugInTenant("todos", TENANT_A)).thenReturn(Optional.empty());
        when(rsRepo.findByAudienceIdentifier("acme:todos"))
                .thenReturn(Optional.of(resourceServer("other", "x", "acme:todos", false)));

        assertThatThrownBy(() -> service.createResourceServer(ctxA,
                new CreateResourceServerCommand("todos", "Todos API", "acme:todos")))
                .isInstanceOf(AudienceIdentifierConflictException.class);
        verify(rsRepo, never()).save(any());
    }

    @Test
    void createStandaloneResourceServerIsNotAutoCreated() {
        when(rsRepo.findBySlugInTenant("todos", TENANT_A)).thenReturn(Optional.empty());
        when(rsRepo.findByAudienceIdentifier("acme:todos")).thenReturn(Optional.empty());

        ResourceServerView view = service.createResourceServer(ctxA,
                new CreateResourceServerCommand("todos", "Todos API", "acme:todos"));

        assertThat(view.autoCreated()).isFalse();
        assertThat(view.slug()).isEqualTo("todos");
        assertThat(view.audienceIdentifier()).isEqualTo("acme:todos");
    }

    // ---- auto-creation: RS + default scope + client link ------------------

    @Test
    void autoCreateForClientBuildsResourceServerDefaultScopeAndClientLink() {
        when(rsRepo.findBySlugInTenant(anyString(), any())).thenReturn(Optional.empty());
        when(clientAuthRepo.findByClientRegisteredIdAndResourceServerId(anyString(), any()))
                .thenReturn(Optional.empty());

        ResourceServerView view = service.autoCreateForClient(ctxA, "client-pk-123", "TodoMaster API");

        // 1) the resource server
        ArgumentCaptor<ResourceServer> rsCaptor = ArgumentCaptor.forClass(ResourceServer.class);
        verify(rsRepo).save(rsCaptor.capture());
        ResourceServer rs = rsCaptor.getValue();
        assertThat(rs.isAutoCreated()).isTrue();
        assertThat(rs.getSlug()).isEqualTo("todomaster-api");
        assertThat(rs.getAudienceIdentifier()).isEqualTo("https://acme.rs.closeauth.io/todomaster-api");
        assertThat(view.autoCreated()).isTrue();

        // 2) the single default scope (read, is_default) — NOT openid/profile/email
        ArgumentCaptor<ResourceServerScope> scopeCaptor = ArgumentCaptor.forClass(ResourceServerScope.class);
        verify(scopeRepo).save(scopeCaptor.capture());
        ResourceServerScope scope = scopeCaptor.getValue();
        assertThat(scope.getScopeName()).isEqualTo("read");
        assertThat(scope.isDefault()).isTrue();

        // 3) the client → RS authorization
        ArgumentCaptor<ClientAuthorizedResourceServer> linkCaptor =
                ArgumentCaptor.forClass(ClientAuthorizedResourceServer.class);
        verify(clientAuthRepo).save(linkCaptor.capture());
        ClientAuthorizedResourceServer link = linkCaptor.getValue();
        assertThat(link.getClientRegisteredId()).isEqualTo("client-pk-123");
        assertThat(link.getResourceServerId()).isEqualTo(rs.getId());
        assertThat(link.getAuthorizedScopes()).isNull();
    }

    // ---- scope catalog: uniqueness ----------------------------------------

    @Test
    void addScopeRejectsDuplicateScopeNameForResourceServer() {
        ResourceServer rs = resourceServer("todos", "Todos", "acme:todos", false);
        when(rsRepo.findByIdInTenant(rs.getId(), TENANT_A)).thenReturn(Optional.of(rs));
        when(scopeRepo.existsByResourceServer_IdAndScopeName(rs.getId(), "read")).thenReturn(true);

        assertThatThrownBy(() -> service.addScope(ctxA, rs.getId(),
                new AddScopeCommand("read", "Read", true, false)))
                .isInstanceOf(ScopeNameConflictException.class);
        verify(scopeRepo, never()).save(any());
    }

    @Test
    void addScopePersistsNewScope() {
        ResourceServer rs = resourceServer("todos", "Todos", "acme:todos", false);
        when(rsRepo.findByIdInTenant(rs.getId(), TENANT_A)).thenReturn(Optional.of(rs));
        when(scopeRepo.existsByResourceServer_IdAndScopeName(rs.getId(), "write")).thenReturn(false);

        ScopeView view = service.addScope(ctxA, rs.getId(),
                new AddScopeCommand("write", "Write access", false, true));

        assertThat(view.scopeName()).isEqualTo("write");
        assertThat(view.requiresConsent()).isTrue();
        assertThat(view.resourceServerId()).isEqualTo(rs.getId());
        verify(scopeRepo).save(any(ResourceServerScope.class));
    }

    // ---- audience immutability --------------------------------------------

    @Test
    void updateChangesNameAndSlugButNeverAudience() {
        ResourceServer rs = resourceServer("old", "Old Name", "acme:immutable", false);
        when(rsRepo.findByIdInTenant(rs.getId(), TENANT_A)).thenReturn(Optional.of(rs));
        when(rsRepo.findBySlugInTenant("new", TENANT_A)).thenReturn(Optional.empty());

        ResourceServerView view = service.updateResourceServer(ctxA, rs.getId(),
                new UpdateResourceServerCommand("New Name", "new"));

        assertThat(view.name()).isEqualTo("New Name");
        assertThat(view.slug()).isEqualTo("new");
        assertThat(view.audienceIdentifier()).isEqualTo("acme:immutable"); // unchanged — no field to change it
    }

    // ---- tenant isolation --------------------------------------------------

    @Test
    void getByIdInAnotherTenantIsNotFound() {
        UUID rsId = UUID.randomUUID();
        when(rsRepo.findByIdInTenant(rsId, TENANT_A)).thenReturn(Optional.empty()); // not in this tenant

        assertThatThrownBy(() -> service.getResourceServerById(ctxA, rsId))
                .isInstanceOf(ResourceServerNotFoundException.class);
    }
}
