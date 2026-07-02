package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.exception.InvalidTenantStateTransitionException;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.repository.TenantRepository;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level exercise of the lifecycle transitions with a mocked repository: every
 * {@code activate}/{@code suspend}/{@code delete} against every starting status, asserting
 * valid transitions mutate the tenant and invalid ones throw without mutating it.
 */
class TenantServiceTest {

    private TenantRepository tenantRepository;
    private TenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantRepository = Mockito.mock(TenantRepository.class);
        // Real Bean Validation provider (Hibernate Validator) so the service-side validation
        // exercises the actual annotations on the command records.
        CommandValidator commandValidator =
                new CommandValidator(Validation.buildDefaultValidatorFactory().getValidator());
        tenantService = new TenantService(
                tenantRepository, List.of(), new TenantStateMachine(), commandValidator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "Bad_Slug", "-leadinghyphen", "UPPER"})
    void provisionRejectsStructurallyInvalidSlug(String badSlug) {
        assertThatThrownBy(() -> tenantService.provisionTenant(new ProvisionTenantCommand(badSlug, "Acme Corp")))
                .isInstanceOfSatisfying(CloseAuthDomainException.class,
                        e -> assertThat(e.getCategory()).isEqualTo(ErrorCategory.VALIDATION));
        // Validation runs before any business logic / persistence.
        verify(tenantRepository, never()).existsBySlug(org.mockito.ArgumentMatchers.anyString());
        verify(tenantRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void provisionRejectsBlankName() {
        assertThatThrownBy(() -> tenantService.provisionTenant(new ProvisionTenantCommand("acme", "  ")))
                .isInstanceOfSatisfying(CloseAuthDomainException.class,
                        e -> assertThat(e.getCategory()).isEqualTo(ErrorCategory.VALIDATION));
        verify(tenantRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private Tenant tenantWithStatus(TenantStatus status) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setSlug("acme");
        t.setName("Acme Corp");
        t.setStatus(status);
        return t;
    }

    @ParameterizedTest
    @EnumSource(TenantStatus.class)
    void activate(TenantStatus from) {
        Tenant tenant = tenantWithStatus(from);
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        boolean valid = from == TenantStatus.PROVISIONING || from == TenantStatus.SUSPENDED;

        if (valid) {
            TenantView view = tenantService.activateTenant(tenant.getId());
            assertThat(view.status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(tenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        } else {
            assertThatThrownBy(() -> tenantService.activateTenant(tenant.getId()))
                    .isInstanceOf(InvalidTenantStateTransitionException.class);
            assertThat(tenant.getStatus()).as("must not mutate on rejected transition").isEqualTo(from);
        }
    }

    @ParameterizedTest
    @EnumSource(TenantStatus.class)
    void suspend(TenantStatus from) {
        Tenant tenant = tenantWithStatus(from);
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        boolean valid = from == TenantStatus.ACTIVE;

        if (valid) {
            TenantView view = tenantService.suspendTenant(tenant.getId());
            assertThat(view.status()).isEqualTo(TenantStatus.SUSPENDED);
            assertThat(tenant.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        } else {
            assertThatThrownBy(() -> tenantService.suspendTenant(tenant.getId()))
                    .isInstanceOf(InvalidTenantStateTransitionException.class);
            assertThat(tenant.getStatus()).as("must not mutate on rejected transition").isEqualTo(from);
        }
    }

    @ParameterizedTest
    @EnumSource(TenantStatus.class)
    void delete(TenantStatus from) {
        Tenant tenant = tenantWithStatus(from);
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
        boolean valid = from != TenantStatus.DELETED;

        if (valid) {
            TenantView view = tenantService.deleteTenant(tenant.getId());
            assertThat(view.status()).isEqualTo(TenantStatus.DELETED);
            assertThat(tenant.getStatus()).isEqualTo(TenantStatus.DELETED);
            assertThat(tenant.getDeletedAt()).as("soft delete stamps deleted_at").isNotNull();
        } else {
            assertThatThrownBy(() -> tenantService.deleteTenant(tenant.getId()))
                    .isInstanceOf(InvalidTenantStateTransitionException.class);
            assertThat(tenant.getStatus()).isEqualTo(TenantStatus.DELETED);
            assertThat(tenant.getDeletedAt()).as("no stamp on rejected delete").isNull();
        }
    }
}
