package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.exception.TenantNotFoundException;
import com.anterka.closeauthbackend.common.exception.TenantSlugConflictException;
import com.anterka.closeauthbackend.common.exception.TenantSuspendedException;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
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

    // ---------------------------------------------------------------------
    // Provisioning
    // ---------------------------------------------------------------------

    /**
     * Provisions a new tenant in status {@code PROVISIONING} (it does NOT auto-activate;
     * call {@link #activateTenant(UUID)} to move it to {@code ACTIVE}).
     *
     * @throws TenantSlugConflictException if the slug is already taken (category CONFLICT)
     */
    @Transactional
    public TenantView provisionTenant(ProvisionTenantCommand command) {
        // Convention 5 (service-side): structural validation for non-HTTP callers, using the
        // same Bean Validation annotations the Stage 7 HTTP edge enforces. Defense-in-depth.
        commandValidator.validate(command);

        if (tenantRepository.existsBySlug(command.slug())) {
            throw new TenantSlugConflictException(command.slug());
        }

        Tenant tenant = new Tenant();
        tenant.setSlug(command.slug());
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
        // Managed entity: the change flushes on commit (dirty checking); no explicit save.
        return TenantView.from(tenant);
    }

    private TenantView transition(UUID tenantId, TenantStatus target) {
        Tenant tenant = loadOrThrow(tenantId);
        stateMachine.checkTransition(tenant.getStatus(), target);
        tenant.setStatus(target);
        return TenantView.from(tenant);
    }

    // ---------------------------------------------------------------------
    // Lookups
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TenantView getTenantById(UUID tenantId) {
        return TenantView.from(loadOrThrow(tenantId));
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
