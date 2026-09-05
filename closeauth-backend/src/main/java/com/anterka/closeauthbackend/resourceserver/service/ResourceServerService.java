package com.anterka.closeauthbackend.resourceserver.service;

import com.anterka.closeauthbackend.common.exception.AudienceIdentifierConflictException;
import com.anterka.closeauthbackend.common.exception.ClientAuthorizationConflictException;
import com.anterka.closeauthbackend.common.exception.ResourceServerDeletionNotAllowedException;
import com.anterka.closeauthbackend.common.exception.ResourceServerNotFoundException;
import com.anterka.closeauthbackend.common.exception.ResourceServerSlugConflictException;
import com.anterka.closeauthbackend.common.exception.ScopeNameConflictException;
import com.anterka.closeauthbackend.common.exception.ScopeNotFoundException;
import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.resourceserver.dto.AddScopeCommand;
import com.anterka.closeauthbackend.resourceserver.dto.ClientAuthorizationView;
import com.anterka.closeauthbackend.resourceserver.dto.CreateResourceServerCommand;
import com.anterka.closeauthbackend.resourceserver.dto.ResourceServerView;
import com.anterka.closeauthbackend.resourceserver.dto.ScopeView;
import com.anterka.closeauthbackend.resourceserver.dto.UpdateResourceServerCommand;
import com.anterka.closeauthbackend.resourceserver.dto.UpdateScopeCommand;
import com.anterka.closeauthbackend.resourceserver.entity.ClientAuthorizedResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServer;
import com.anterka.closeauthbackend.resourceserver.entity.ResourceServerScope;
import com.anterka.closeauthbackend.resourceserver.repository.ClientAuthorizedResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerRepository;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeCountProjection;
import com.anterka.closeauthbackend.resourceserver.repository.ResourceServerScopeRepository;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resource Server domain service (Section 7.6): CRUD, the scope catalog, the auto-creation capability, and
 * client→RS authorizations. Follows the Stage 3a/3b conventions.
 *
 * <p><b>Immutability:</b> {@code audience_identifier} is immutable after creation (a stable contract with
 * resource servers — enforced structurally by omitting it from {@link UpdateResourceServerCommand});
 * {@code slug} is mutable (cheap rename — scope names are stored bare); {@code scope_name} is immutable.
 *
 * <p><b>auto-creation is a capability, not a trigger:</b> {@link #autoCreateForClient} is a callable primitive
 * that Stage 4/7 will invoke from client registration. 3c-i does not wire it to any flow (none exists yet).
 */
@Service
@RequiredArgsConstructor
public class ResourceServerService {

    private final ResourceServerRepository resourceServerRepository;
    private final ResourceServerScopeRepository scopeRepository;
    private final ClientAuthorizedResourceServerRepository clientAuthorizationRepository;
    private final TenantService tenantService;
    private final CommandValidator commandValidator;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    // ---------------------------------------------------------------------
    // Resource Server CRUD
    // ---------------------------------------------------------------------

    /** Explicit standalone RS creation ({@code is_auto_created = false}). */
    @Transactional
    public ResourceServerView createResourceServer(TenantContext context, CreateResourceServerCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        requireSlugAvailable(context.tenantId(), command.slug());
        requireAudienceAvailable(command.audienceIdentifier());

        ResourceServer rs = new ResourceServer();
        rs.setTenantId(context.tenantId());
        rs.setSlug(command.slug());
        rs.setName(command.name());
        rs.setAudienceIdentifier(command.audienceIdentifier());
        rs.setAutoCreated(false);
        ResourceServer saved = resourceServerRepository.save(rs);
        auditEmitter.emit(AuditEvents.resourceServerCreated(context.tenantId(), saved.getId(), saved.getName()));
        return ResourceServerView.from(saved);
    }

    /** Updates the mutable fields (name, slug). Audience is immutable (not in the command). */
    @Transactional
    public ResourceServerView updateResourceServer(TenantContext context, UUID resourceServerId,
                                                   UpdateResourceServerCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        ResourceServer rs = loadResourceServerOrThrow(context, resourceServerId);

        if (!command.slug().equals(rs.getSlug())) {
            requireSlugAvailable(context.tenantId(), command.slug());
            rs.setSlug(command.slug());
        }
        rs.setName(command.name());
        return ResourceServerView.from(rs);
    }

    /**
     * Hard-deletes a standalone resource server; the DDL cascades its scopes and client authorizations.
     * Direct deletion of an auto-created RS is refused — its lifecycle is tied to its client, see
     * {@link #deleteAutoCreatedForClient}, the reachable path this exception's message points to.
     *
     * <p>{@code audit_events.resource_server_id}'s {@code ON DELETE RESTRICT} was relaxed in
     * {@code V3__relax_audit_actor_fks.sql} for exactly this reason: an RS with any audit history
     * (create, {@code SCOPE_DEFINED}/{@code SCOPE_REMOVED}) would otherwise never be hard-deletable.
     * Application-role dependencies (3c-ii) cascade per the DDL.
     */
    @Transactional
    public void deleteResourceServer(TenantContext context, UUID resourceServerId) {
        tenantService.requireActiveTenant(context);
        ResourceServer rs = loadResourceServerOrThrow(context, resourceServerId);
        if (rs.isAutoCreated()) {
            throw new ResourceServerDeletionNotAllowedException(resourceServerId);
        }
        resourceServerRepository.delete(rs);
    }

    /**
     * The reachable path {@link ResourceServerDeletionNotAllowedException} points operators to: removes a
     * client's 1:1 auto-created resource server as part of {@code ClientRegistrationService.deleteClient}.
     * Nothing else cascades this — {@code resource_servers} carries no FK to the client, only the
     * {@code client_authorized_resource_servers} join row does (and that row cascades from EITHER side, so
     * deleting the RS here is sufficient; the join row disappears with it). Idempotent: a client with no
     * auto-created RS (the platform-managed {@code admin-console-*} client, or one predating this callback)
     * is a no-op, not an error.
     *
     * <p>Tenant-scoped defense in depth, same posture as every other lookup in this module: a link pointing
     * at another tenant's RS (should never happen — a client belongs to exactly one tenant) is filtered out
     * rather than trusted.
     */
    @Transactional
    public void deleteAutoCreatedForClient(TenantContext context, String clientRegisteredId) {
        clientAuthorizationRepository.findByClientRegisteredId(clientRegisteredId).stream()
                .map(link -> resourceServerRepository.findById(link.getResourceServerId()))
                .flatMap(Optional::stream)
                .filter(rs -> rs.isAutoCreated() && context.tenantId().equals(rs.getTenantId()))
                .forEach(resourceServerRepository::delete);
    }

    @Transactional(readOnly = true)
    public ResourceServerView getResourceServerById(TenantContext context, UUID resourceServerId) {
        return ResourceServerView.from(loadResourceServerOrThrow(context, resourceServerId));
    }

    @Transactional(readOnly = true)
    public ResourceServerView getResourceServerBySlug(TenantContext context, String slug) {
        ResourceServer rs = resourceServerRepository.findBySlugInTenant(slug, context.tenantId())
                .orElseThrow(() -> new ResourceServerNotFoundException("slug", slug));
        return ResourceServerView.from(rs);
    }

    /**
     * Tenant-unscoped by design: {@code audience_identifier} is globally unique, so it self-identifies exactly
     * one RS. This is how Stage 4 resolves an incoming {@code aud} to its resource server; there is no tenant to
     * scope by until the RS is found (it then carries its own {@code tenantId}).
     */
    @Transactional(readOnly = true)
    public ResourceServerView getResourceServerByAudience(String audienceIdentifier) {
        ResourceServer rs = resourceServerRepository.findByAudienceIdentifier(audienceIdentifier)
                .orElseThrow(() -> new ResourceServerNotFoundException("audienceIdentifier", audienceIdentifier));
        return ResourceServerView.from(rs);
    }

    /**
     * FE-4b (spec §6.4.4's "Scope count" list column): decorates each row's {@code scopeCount} from one bulk
     * query (never per-row) — safe to do inside this module, unlike the "used by N roles" scope-catalog
     * decoration (that one needs {@code rbac}, a dependency this module deliberately doesn't have).
     */
    @Transactional(readOnly = true)
    public List<ResourceServerView> listResourceServers(TenantContext context) {
        var scopeCounts = scopeRepository.countScopesByTenant(context.tenantId()).stream()
                .collect(Collectors.toMap(
                        ResourceServerScopeCountProjection::getResourceServerId,
                        ResourceServerScopeCountProjection::getScopeCount));
        return resourceServerRepository.findByTenantId(context.tenantId()).stream()
                .map(ResourceServerView::from)
                .map(view -> view.withScopeCount(scopeCounts.getOrDefault(view.id(), 0L)))
                .toList();
    }

    // ---------------------------------------------------------------------
    // Auto-creation capability (Section 7.6). CAPABILITY, not a trigger — Stage 4/7 calls it.
    // ---------------------------------------------------------------------

    /**
     * Creates a 1:1 auto-created resource server for a freshly-registered client: an RS with
     * {@code is_auto_created = true}, a client-derived slug, a globally-unique audience
     * ({@code {tenantSlug}:{rsSlug}}), one default {@code read} scope, and the client→RS authorization.
     *
     * <p>{@code clientRegisteredId} is the SAS {@code oauth2_registered_client(id)} PK, trusted from the caller
     * (Stage 4/7) — it is NOT validated against the SAS table here (that table is not JPA-managed pre-Stage-4).
     */
    @Transactional
    public ResourceServerView autoCreateForClient(TenantContext context, String clientRegisteredId, String clientName) {
        TenantView tenant = tenantService.requireActiveTenant(context);

        String slug = deriveUniqueSlug(context.tenantId(), clientName);
        // Conformant https:// URI audience (RFC 7519 / matches Auth0/Azure/Google). Globally unique:
        // the tenant slug is globally unique, so the host {tenantSlug}.{base} is too.
        // NOTE: the tenant slug embedded here is FROZEN at creation — audience_identifier is immutable,
        // so a later tenant-slug rename does NOT rewrite this audience (a stable contract, like Auth0
        // API identifiers). Cosmetic drift after a rename is expected and acceptable.
        String host = tenant.slug() + "." + properties.getResourceServer().getAudienceHostBase();
        String audience = "https://" + host + "/" + slug;

        ResourceServer rs = new ResourceServer();
        rs.setTenantId(context.tenantId());
        rs.setSlug(slug);
        rs.setName(hasText(clientName) ? clientName.trim() : slug);
        rs.setAudienceIdentifier(audience);
        rs.setAutoCreated(true);
        ResourceServer saved = resourceServerRepository.save(rs);

        // A minimal default scope so a freshly-registered client has something to request. Deliberately NOT
        // openid/profile/email/offline_access — those are PLATFORM scopes (SAS/Stage 4), not RS-owned scopes.
        persistScope(saved, "read", "Default read scope", true, false);

        // Link the client to its own auto-created RS (all scopes).
        persistClientAuthorization(clientRegisteredId, saved.getId(), null);

        return ResourceServerView.from(saved);
    }

    // ---------------------------------------------------------------------
    // Scope catalog (kept in this service — scopes are within the RS aggregate)
    // ---------------------------------------------------------------------

    @Transactional
    public ScopeView addScope(TenantContext context, UUID resourceServerId, AddScopeCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        ResourceServer rs = loadResourceServerOrThrow(context, resourceServerId); // ensures RS ∈ tenant
        if (scopeRepository.existsByResourceServer_IdAndScopeName(resourceServerId, command.scopeName())) {
            throw new ScopeNameConflictException(resourceServerId, command.scopeName());
        }
        ResourceServerScope scope = persistScope(rs, command.scopeName(), command.description(),
                command.isDefault(), command.requiresConsent());
        auditEmitter.emit(AuditEvents.scopeDefined(context.tenantId(), resourceServerId, command.scopeName()));
        return ScopeView.from(scope, resourceServerId);
    }

    @Transactional
    public ScopeView updateScope(TenantContext context, UUID resourceServerId, UUID scopeId, UpdateScopeCommand command) {
        commandValidator.validate(command);
        tenantService.requireActiveTenant(context);
        loadResourceServerOrThrow(context, resourceServerId); // ensures RS ∈ tenant
        ResourceServerScope scope = loadScopeOrThrow(resourceServerId, scopeId);
        scope.setDescription(command.description());
        scope.setDefault(command.isDefault());
        scope.setRequiresConsent(command.requiresConsent());
        return ScopeView.from(scope, resourceServerId);
    }

    @Transactional
    public void removeScope(TenantContext context, UUID resourceServerId, UUID scopeId) {
        tenantService.requireActiveTenant(context);
        loadResourceServerOrThrow(context, resourceServerId); // ensures RS ∈ tenant
        ResourceServerScope scope = loadScopeOrThrow(resourceServerId, scopeId);
        // Note: once 3c-ii's application_role_scopes reference scopes, removing an in-use scope will be
        // constrained by that FK; for now this is a straightforward removal.
        scopeRepository.delete(scope);
        auditEmitter.emit(AuditEvents.scopeRemoved(context.tenantId(), resourceServerId, scopeId));
    }

    @Transactional(readOnly = true)
    public List<ScopeView> listScopes(TenantContext context, UUID resourceServerId) {
        loadResourceServerOrThrow(context, resourceServerId); // ensures RS ∈ tenant
        return scopeRepository.findByResourceServer_Id(resourceServerId).stream()
                .map(scope -> ScopeView.from(scope, resourceServerId))
                .toList();
    }

    // ---------------------------------------------------------------------
    // client_authorized_resource_servers
    // ---------------------------------------------------------------------

    @Transactional
    public ClientAuthorizationView authorizeClientForResourceServer(TenantContext context, String clientRegisteredId,
                                                                    UUID resourceServerId, String authorizedScopes) {
        tenantService.requireActiveTenant(context);
        loadResourceServerOrThrow(context, resourceServerId); // ensures RS ∈ tenant (client PK is trusted)
        ClientAuthorizedResourceServer link = persistClientAuthorization(clientRegisteredId, resourceServerId, authorizedScopes);
        return ClientAuthorizationView.from(link);
    }

    /** Idempotent: removes the link if present, otherwise does nothing. */
    @Transactional
    public void revokeClientAuthorization(TenantContext context, String clientRegisteredId, UUID resourceServerId) {
        tenantService.requireActiveTenant(context);
        loadResourceServerOrThrow(context, resourceServerId); // ensures RS ∈ tenant
        clientAuthorizationRepository
                .findByClientRegisteredIdAndResourceServerId(clientRegisteredId, resourceServerId)
                .ifPresent(clientAuthorizationRepository::delete);
    }

    /**
     * Lists the resource servers a client may request tokens for. Tenant-consistent by construction: a link is
     * only created for RSes in the caller's tenant, and a client belongs to a single tenant.
     */
    @Transactional(readOnly = true)
    public List<ClientAuthorizationView> listAuthorizedResourceServers(TenantContext context, String clientRegisteredId) {
        return clientAuthorizationRepository.findByClientRegisteredId(clientRegisteredId).stream()
                .map(ClientAuthorizationView::from)
                .toList();
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private ResourceServer loadResourceServerOrThrow(TenantContext context, UUID resourceServerId) {
        return resourceServerRepository.findByIdInTenant(resourceServerId, context.tenantId())
                .orElseThrow(() -> new ResourceServerNotFoundException("id", resourceServerId));
    }

    private ResourceServerScope loadScopeOrThrow(UUID resourceServerId, UUID scopeId) {
        return scopeRepository.findByIdAndResourceServer_Id(scopeId, resourceServerId)
                .orElseThrow(() -> new ScopeNotFoundException(resourceServerId, scopeId));
    }

    private void requireSlugAvailable(UUID tenantId, String slug) {
        if (resourceServerRepository.findBySlugInTenant(slug, tenantId).isPresent()) {
            throw new ResourceServerSlugConflictException(slug);
        }
    }

    private void requireAudienceAvailable(String audienceIdentifier) {
        if (resourceServerRepository.findByAudienceIdentifier(audienceIdentifier).isPresent()) {
            throw new AudienceIdentifierConflictException(audienceIdentifier);
        }
    }

    private ResourceServerScope persistScope(ResourceServer rs, String scopeName, String description,
                                             boolean isDefault, boolean requiresConsent) {
        ResourceServerScope scope = new ResourceServerScope();
        scope.setResourceServer(rs);
        scope.setScopeName(scopeName);
        scope.setDescription(description);
        scope.setDefault(isDefault);
        scope.setRequiresConsent(requiresConsent);
        return scopeRepository.save(scope);
    }

    private ClientAuthorizedResourceServer persistClientAuthorization(String clientRegisteredId, UUID resourceServerId,
                                                                      String authorizedScopes) {
        if (clientAuthorizationRepository
                .findByClientRegisteredIdAndResourceServerId(clientRegisteredId, resourceServerId).isPresent()) {
            throw new ClientAuthorizationConflictException(clientRegisteredId, resourceServerId);
        }
        ClientAuthorizedResourceServer link = new ClientAuthorizedResourceServer();
        link.setClientRegisteredId(clientRegisteredId);
        link.setResourceServerId(resourceServerId);
        link.setAuthorizedScopes(authorizedScopes);
        return clientAuthorizationRepository.save(link);
    }

    /** Derives a DNS-safe slug from the client name, made unique within the tenant with a short suffix if needed. */
    private String deriveUniqueSlug(UUID tenantId, String clientName) {
        String base = toSlug(clientName);
        if (resourceServerRepository.findBySlugInTenant(base, tenantId).isEmpty()) {
            return base;
        }
        String truncated = base.length() > 55 ? base.substring(0, 55) : base;
        return truncated + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private static String toSlug(String input) {
        if (!hasText(input)) {
            return "app";
        }
        String slug = input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "app";
        }
        if (slug.length() > 63) {
            slug = slug.substring(0, 63).replaceAll("-+$", "");
        }
        return slug;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
