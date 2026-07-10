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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantBrandingServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final TenantContext ctx = TenantContext.of(tenantId);

    private TenantBrandingRepository repository;
    private CloseAuthProperties properties;
    private TenantBrandingService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(TenantBrandingRepository.class);
        CommandValidator validator = Mockito.mock(CommandValidator.class); // color @Pattern is defense-in-depth; no-op here
        properties = new CloseAuthProperties();
        service = new TenantBrandingService(repository, validator, properties,
                org.mockito.Mockito.mock(com.anterka.closeauthbackend.audit.service.AuditEmitter.class));
        when(repository.save(any(TenantBranding.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void resolutionFillsNullFieldsWithPlatformDefaults() {
        TenantBranding branding = new TenantBranding();
        branding.setTenantId(tenantId);
        branding.setPrimaryColor("#123456"); // tenant-set; others null
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.of(branding));

        BrandingView view = service.resolveForTenant(tenantId);

        assertThat(view.primaryColor()).isEqualTo("#123456");                               // tenant value kept
        assertThat(view.backgroundColor()).isEqualTo(properties.getBranding().getBackgroundColor()); // default filled
        assertThat(view.accentColor()).isEqualTo(properties.getBranding().getAccentColor());
    }

    @Test
    void resolutionForATenantWithoutABrandingRowReturnsPlatformDefaults() {
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        BrandingView view = service.resolveForTenant(tenantId);
        assertThat(view.primaryColor()).isEqualTo(properties.getBranding().getPrimaryColor());
        assertThat(view).isEqualTo(service.platformDefault());
    }

    @Test
    void updateRejectsANonHttpsLogoUrl() {
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        UpdateBrandingCommand command = new UpdateBrandingCommand(
                "http://cdn.example/logo.png", "#4F46E5", null, null, "Acme"); // http, not https

        assertThatThrownBy(() -> service.updateBranding(ctx, command))
                .isInstanceOf(CloseAuthDomainException.class)
                .satisfies(e -> assertThat(((CloseAuthDomainException) e).getCategory()).isEqualTo(ErrorCategory.VALIDATION));
        verify(repository, never()).save(any());
    }

    @Test
    void updateUpsertsValidBrandingAndReturnsResolvedView() {
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        UpdateBrandingCommand command = new UpdateBrandingCommand(
                "https://cdn.example/logo.png", "#4F46E5", "#FFFFFF", "#22D3EE", "Acme");

        BrandingView view = service.updateBranding(ctx, command);

        assertThat(view.logoUrl()).isEqualTo("https://cdn.example/logo.png");
        assertThat(view.companyName()).isEqualTo("Acme");
        assertThat(view.primaryColor()).isEqualTo("#4F46E5");
        verify(repository).save(any(TenantBranding.class));
    }
}
