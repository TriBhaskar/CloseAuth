package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.tenant.dto.BrandingView;
import com.anterka.closeauthbackend.tenant.dto.UpdateBrandingCommand;
import com.anterka.closeauthbackend.tenant.entity.TenantBranding;
import com.anterka.closeauthbackend.tenant.repository.TenantBrandingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.UUID;

/**
 * Tenant branding (§7.1, Stage 6b-ii) — read resolution for the hosted pages + tenant-scoped update (the admin HTTP
 * endpoint that calls {@link #updateBranding} is Stage 7; the public resolution is exposed now).
 *
 * <p><b>Resolution never leaks tenant internals</b> — it returns only the {@link BrandingView} presentation fields,
 * with platform defaults filling nulls. <b>Strict validation on write</b> (hex colors via the command's
 * {@code @Pattern}; {@code logo_url} must be a well-formed https URL) is the first layer of the branding-injection
 * defense; safe injection (CSS-escaping/CSP) in the UI layer is the second.
 */
@Service
@RequiredArgsConstructor
public class TenantBrandingService {

    private final TenantBrandingRepository repository;
    private final CommandValidator commandValidator;
    private final CloseAuthProperties properties;

    /** Public resolution: a tenant's branding with platform defaults for null fields (never any tenant internals). */
    @Transactional(readOnly = true)
    public BrandingView resolveForTenant(UUID tenantId) {
        return repository.findByTenantId(tenantId).map(this::toView).orElseGet(this::platformDefault);
    }

    /** Platform-default branding — returned for an unknown/unbranded client, so resolution never reveals existence. */
    public BrandingView platformDefault() {
        CloseAuthProperties.Branding d = properties.getBranding();
        return new BrandingView(d.getDefaultLogoUrl(), d.getPrimaryColor(), d.getBackgroundColor(),
                d.getAccentColor(), d.getCompanyNameFallback());
    }

    /** Admin read of a tenant's (resolved) branding — Stage 7 endpoint. */
    @Transactional(readOnly = true)
    public BrandingView getBranding(TenantContext context) {
        return resolveForTenant(context.tenantId());
    }

    /** Updates a tenant's branding (tenant-scoped, strictly validated). Upserts the 1:1 row. */
    @Transactional
    public BrandingView updateBranding(TenantContext context, UpdateBrandingCommand command) {
        commandValidator.validate(command); // hex colors (@Pattern), sizes
        validateLogoUrl(command.logoUrl());

        TenantBranding branding = repository.findByTenantId(context.tenantId()).orElseGet(() -> {
            TenantBranding fresh = new TenantBranding();
            fresh.setTenantId(context.tenantId());
            return fresh;
        });
        branding.setLogoUrl(blankToNull(command.logoUrl()));
        branding.setPrimaryColor(blankToNull(command.primaryColor()));
        branding.setBackgroundColor(blankToNull(command.backgroundColor()));
        branding.setAccentColor(blankToNull(command.accentColor()));
        branding.setCompanyName(blankToNull(command.companyName()));
        return toView(repository.save(branding));
    }

    private BrandingView toView(TenantBranding b) {
        CloseAuthProperties.Branding d = properties.getBranding();
        return new BrandingView(
                b.getLogoUrl() != null ? b.getLogoUrl() : d.getDefaultLogoUrl(),
                b.getPrimaryColor() != null ? b.getPrimaryColor() : d.getPrimaryColor(),
                b.getBackgroundColor() != null ? b.getBackgroundColor() : d.getBackgroundColor(),
                b.getAccentColor() != null ? b.getAccentColor() : d.getAccentColor(),
                b.getCompanyName() != null ? b.getCompanyName() : d.getCompanyNameFallback());
    }

    /** A logo URL must be a well-formed absolute <b>https</b> URL (http would be mixed-content on the https page). */
    private void validateLogoUrl(String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(logoUrl);
        } catch (IllegalArgumentException malformed) {
            throw invalid("branding.invalid_logo_url", "logo_url is not a valid URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw invalid("branding.logo_url_not_https", "logo_url must be an absolute https URL");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private CloseAuthDomainException invalid(String code, String message) {
        return new CloseAuthDomainException(ErrorCategory.VALIDATION, code, message);
    }
}
