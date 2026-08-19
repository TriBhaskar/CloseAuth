package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.exception.TenantNotFoundException;
import com.anterka.closeauthbackend.common.exception.TenantSlugConflictException;
import com.anterka.closeauthbackend.common.exception.TenantSuspendedException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.tenant.dto.EntryResolutionView;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.repository.TenantRepository;
import com.anterka.closeauthbackend.token.service.TokenRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant lifecycle domain service (Section 7.1). Reference implementation of the Stage 3a
 * service-layer conventions:
 *
 * <ol>
 *   <li><b>Structure &amp; transactions:</b> {@code @Service}; {@code @Transactional} at the
 *       method level, {@code readOnly = true} for reads.</li>
 *   <li><b>Tenant context:</b> administering the Tenant aggregate is keyed by the target
 *       tenant's {@code UUID}. The {@code TenantContext} value object is consumed by the
 *       {@link #requireActiveTenant(TenantContext)} guard that downstream (3b/3c) services use.</li>
 *   <li><b>Domain exceptions:</b> business-rule failures throw {@code CloseAuthDomainException}
 *       subclasses carrying an {@code ErrorCategory} — never HTTP.</li>
 *   <li><b>DTOs:</b> accepts commands, returns {@link TenantView}; never leaks the entity.</li>
 *   <li><b>Validation:</b> structural validation is on the command DTO (enforced at Stage 7);
 *       business rules (slug uniqueness, legal transitions) live here.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepository;
    /** Extension seam beans (empty in 3a; 3c adds the role starter-pack implementation). */
    private final List<TenantProvisioningCallback> provisioningCallbacks;
    private final TenantStateMachine stateMachine;
    private final CommandValidator commandValidator;
    private final TenantSlugGenerator tenantSlugGenerator;
    /** 7b/IT-9 integration: kill a tenant's live access tokens on suspension/deletion (parallel to UserService). */
    private final TokenRevocationService tokenRevocationService;
    private final AuditEmitter auditEmitter;

    // ---------------------------------------------------------------------
    // Provisioning
    // ---------------------------------------------------------------------

    /**
     * Provisions a new tenant in status {@code PROVISIONING} (it does NOT auto-activate;
     * call {@link #activateTenant(UUID)} to move it to {@code ACTIVE}). The public Tenant ID is
     * server-derived from {@code command.name()} by {@link TenantSlugGenerator} — see spec §1.2.
     *
     * @throws TenantSlugConflictException if a unique Tenant ID could not be generated
     *                                      (category CONFLICT, practically unreachable)
     */
    @Transactional
    public TenantView provisionTenant(ProvisionTenantCommand command) {
        // Convention 5 (service-side): structural validation for non-HTTP callers, using the
        // same Bean Validation annotations the Stage 7 HTTP edge enforces. Defense-in-depth.
        commandValidator.validate(command);

        String slug = tenantSlugGenerator.generate(command.name(), tenantRepository::existsBySlug);

        Tenant tenant = new Tenant();
        tenant.assignSlug(slug);
        tenant.setName(command.name());
        tenant.setStatus(TenantStatus.PROVISIONING);
        Tenant saved = tenantRepository.save(tenant);

        // --- STAGE 3c extension seam ---
        // Fire provisioning callbacks within this transaction. 3c contributes a
        // TenantProvisioningCallback @Component that creates the default role/scope
        // starter-pack for the new tenant; it commits atomically with the tenant row.
        TenantContext context = TenantContext.of(saved.getId());
        for (TenantProvisioningCallback callback : provisioningCallbacks) {
            callback.onTenantProvisioned(saved, context);
        }

        auditEmitter.emit(AuditEvents.tenantCreated(saved.getId(), saved.getSlug(), saved.getName()));
        return TenantView.from(saved);
    }

    // ---------------------------------------------------------------------
    // Lifecycle state machine (transitions validated by TenantStateMachine)
    // ---------------------------------------------------------------------

    /** {@code PROVISIONING → ACTIVE} or {@code SUSPENDED → ACTIVE}. */
    @Transactional
    public TenantView activateTenant(UUID tenantId) {
        return transition(tenantId, TenantStatus.ACTIVE);
    }

    /** {@code ACTIVE → SUSPENDED}. */
    @Transactional
    public TenantView suspendTenant(UUID tenantId) {
        return transition(tenantId, TenantStatus.SUSPENDED);
    }

    /**
     * Soft delete: {@code PROVISIONING|ACTIVE|SUSPENDED → DELETED}, stamping {@code deleted_at}.
     * The row is retained (hard purge is a separate operational job).
     */
    @Transactional
    public TenantView deleteTenant(UUID tenantId) {
        Tenant tenant = loadOrThrow(tenantId);
        stateMachine.checkTransition(tenant.getStatus(), TenantStatus.DELETED);
        tenant.setStatus(TenantStatus.DELETED);
        tenant.setDeletedAt(Instant.now());
        // IT-9 fix (1a): a deleted tenant is no longer ACTIVE — kill its users' live access tokens now, mirroring
        // UserService.transition. Session validation + refresh rotation are separately gated on tenant status.
        tokenRevocationService.revokeAllTenantTokens(tenantId);
        // Managed entity: the change flushes on commit (dirty checking); no explicit save.
        auditEmitter.emit(AuditEvents.tenantDeleted(tenantId));
        return TenantView.from(tenant);
    }

    private TenantView transition(UUID tenantId, TenantStatus target) {
        Tenant tenant = loadOrThrow(tenantId);
        stateMachine.checkTransition(tenant.getStatus(), target);
        tenant.setStatus(target);
        if (target == TenantStatus.SUSPENDED) {
            // IT-9 fix (1a): kill the tenant's users' live access tokens now, not merely at expiry (mirrors
            // UserService.transition's revokeAllUserTokens). The SSO bypass and refresh path are closed separately
            // (AuthServerSessionService.validateSession + RefreshTokenRotationService.authorizeRotation tenant gates).
            tokenRevocationService.revokeAllTenantTokens(tenantId);
        }
        auditEmitter.emit(target == TenantStatus.ACTIVE
                ? AuditEvents.tenantActivated(tenantId)
                : AuditEvents.tenantSuspended(tenantId));
        return TenantView.from(tenant);
    }

    // ---------------------------------------------------------------------
    // Lookups
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TenantView getTenantById(UUID tenantId) {
        return TenantView.from(loadOrThrow(tenantId));
    }

    /** All tenants (platform-scoped; §7.8 admin list). Not tenant-scoped — a cross-tenant, platform-admin operation. */
    @Transactional(readOnly = true)
    public List<TenantView> listTenants() {
        return tenantRepository.findAll().stream().map(TenantView::from).toList();
    }

    /** Tenant-unscoped by design: {@code slug} is globally unique (request-time resolution). */
    @Transactional(readOnly = true)
    public TenantView getTenantBySlug(String slug) {
        Tenant tenant = tenantRepository.findBySlug(slug)
                .orElseThrow(() -> new TenantNotFoundException("slug", slug));
        return TenantView.from(tenant);
    }

    @Transactional(readOnly = true)
    public boolean existsBySlug(String slug) {
        return tenantRepository.existsBySlug(slug);
    }

    /**
     * FE-2a (spec §6.1): the public, unauthenticated workspace-entry resolution — deliberately
     * {@code Optional}-returning, never throwing, unlike {@link #getTenantBySlug}: this is a
     * pre-authentication existence probe, not an authenticated lookup, so a miss is not
     * exceptional and must never surface a {@code TenantNotFoundException}'s stack trace or
     * problem-detail body to an anonymous caller.
     *
     * <p>Not-found and every non-{@code ACTIVE} status ({@code PROVISIONING}, {@code SUSPENDED},
     * the soft-deleted {@code DELETED}) collapse into the same empty {@code Optional} — the
     * enumeration-safety requirement ("a suspended tenant must not be distinguishable from a
     * nonexistent one") falls out of this method's shape rather than being a branch the caller
     * has to get right.
     */
    @Transactional(readOnly = true)
    public Optional<EntryResolutionView> resolveActiveTenantBySlug(String slug) {
        return tenantRepository.findEntryResolutionBySlug(slug)
                .filter(view -> view.status() == TenantStatus.ACTIVE);
    }

    // ---------------------------------------------------------------------
    // Guard for downstream services (3b/3c)
    // ---------------------------------------------------------------------

    /**
     * The intended guard for every downstream service that acts on tenant-owned data: loads
     * the tenant in the given {@link TenantContext} and asserts it is usable ({@code ACTIVE}).
     *
     * @throws TenantNotFoundException  if the tenant does not exist (category NOT_FOUND)
     * @throws TenantSuspendedException if the tenant is SUSPENDED or DELETED (category FORBIDDEN)
     */
    @Transactional(readOnly = true)
    public TenantView requireActiveTenant(TenantContext context) {
        Tenant tenant = loadOrThrow(context.tenantId());
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new TenantSuspendedException(tenant.getId(), tenant.getStatus());
        }
        return TenantView.from(tenant);
    }

    // ---------------------------------------------------------------------

    private Tenant loadOrThrow(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("id", tenantId));
    }
}
