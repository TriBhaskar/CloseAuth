package com.anterka.closeauthbackend.auth;

import com.anterka.closeauthbackend.auth.dto.BootstrapAdminCommand;
import com.anterka.closeauthbackend.auth.service.TenantOnboardingService;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
import com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand;
import com.anterka.closeauthbackend.tenant.dto.TenantView;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import com.anterka.closeauthbackend.identity.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * The atomicity proof {@code TenantOnboardingServiceTest} (unit, Mockito) cannot give: that a failure AFTER the new
 * user is committed-in-flight but before the transaction actually commits leaves NO trace — the dangerous half-state
 * §2.5 of {@code docs/TENANT_ONBOARDING_UI_ANALYSIS.md} calls out (an active user holding only {@code TENANT_MEMBER}
 * because the {@code TENANT_ADMIN} grant failed after user-creation committed) must be unobservable. Forces
 * {@link TenantRoleService#assignTenantRole} to throw via a {@link MockitoSpyBean}, inside
 * {@code bootstrapFirstAdmin}'s single {@code @Transactional} boundary, then asserts the user simply does not exist.
 *
 * <p>Gated on {@code -Dcloseauth.it.db.url} (needs live Postgres + Redis), same as {@link IdentityFlowsIntegrationTest}
 * — self-skips otherwise rather than failing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "closeauth.it.db.url", matches = ".+")
class TenantOnboardingRollbackIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("closeauth.it.db.url"));
        registry.add("spring.datasource.username", () -> System.getProperty("closeauth.it.db.username"));
        registry.add("spring.datasource.password", () -> System.getProperty("closeauth.it.db.password"));
        registry.add("spring.data.redis.host", () -> System.getProperty("closeauth.it.redis.host"));
        registry.add("spring.data.redis.port", () -> System.getProperty("closeauth.it.redis.port"));
        registry.add("closeauth.issuer-url", () -> "http://localhost:9098");
        registry.add("closeauth.session.cookie.secure", () -> "false");
    }

    @Autowired
    private TenantOnboardingService tenantOnboardingService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private UserService userService;

    @MockitoSpyBean
    private TenantRoleService tenantRoleService;

    @Test
    void bootstrapRollsBackTheNewUserEntirelyWhenTheAdminGrantFailsAfterUserCreationCommitted() {
        TenantView tenant = tenantService.provisionTenant(
                new ProvisionTenantCommand("Rollback IT Tenant"));
        tenantService.activateTenant(tenant.id());
        TenantContext context = TenantContext.of(tenant.id());

        String email = "rollback-" + UUID.randomUUID() + "@example.com";
        Mockito.doThrow(new RuntimeException("simulated TENANT_ADMIN grant failure"))
                .when(tenantRoleService).assignTenantRole(any(), any(), any(), any());

        assertThatThrownBy(() -> tenantOnboardingService.bootstrapFirstAdmin(tenant.id(),
                new BootstrapAdminCommand(email, "First", "Last")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("simulated TENANT_ADMIN grant failure");

        // The whole composite is one @Transactional method — the forced failure after user-creation (already
        // flushed within the same transaction) must roll the entire thing back, not just leave the grant undone.
        assertThat(userService.existsByEmail(context, email))
                .as("a failed grant must roll back the user-creation it followed, not leave an admin-less active user")
                .isFalse();
    }
}
