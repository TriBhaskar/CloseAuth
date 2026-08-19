package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.tenant.dto.BrandingView;
import com.anterka.closeauthbackend.tenant.enums.RegistrationMode;
import com.anterka.closeauthbackend.tenant.service.TenantBrandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-free unit test for {@link BrandingController} (standalone MockMvc — same convention as
 * {@link LoginControllerTest}). Proves the endpoint's own documented contract — "always 200, never reveals whether
 * the client exists" — actually holds for a caller that supplies NO {@code client_id} at all (e.g. a direct/
 * bookmarked navigation to a hosted-auth page, bypassing the normal resolver flow), not just an unknown one. Before
 * the fix this proves, a missing {@code client_id} 500'd via {@code MissingServletRequestParameterException}
 * instead of degrading to platform-default branding.
 */
class BrandingControllerTest {

    private AuthFlowTenantResolver tenantResolver;
    private TenantBrandingService brandingService;
    private MockMvc mockMvc;

    private final BrandingView platformDefault =
            new BrandingView(null, "#000000", "#ffffff", "#000000", "CloseAuth", null, null);

    @BeforeEach
    void setUp() {
        tenantResolver = Mockito.mock(AuthFlowTenantResolver.class);
        brandingService = Mockito.mock(TenantBrandingService.class);
        BrandingController controller = new BrandingController(tenantResolver, brandingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(brandingService.platformDefault()).thenReturn(platformDefault);
    }

    @Test
    void missingClientId_DegradesToPlatformDefault_Never500s() throws Exception {
        mockMvc.perform(get("/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("CloseAuth"));

        verify(tenantResolver, never()).resolveTenantId(any());
    }

    @Test
    void blankClientId_DegradesToPlatformDefault() throws Exception {
        mockMvc.perform(get("/branding").param("client_id", "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("CloseAuth"));

        verify(tenantResolver, never()).resolveTenantId(any());
    }

    @Test
    void unknownClientId_DegradesToPlatformDefault_ByteIdenticalToMissing() throws Exception {
        when(tenantResolver.resolveTenantId("unknown-client")).thenReturn(Optional.empty());

        mockMvc.perform(get("/branding").param("client_id", "unknown-client"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("CloseAuth"));
    }

    @Test
    void knownClientId_ResolvesTenantBranding() throws Exception {
        UUID tenantId = UUID.randomUUID();
        BrandingView tenantBranding = new BrandingView(
                "https://acme.test/logo.png", "#111111", "#eeeeee", "#222222", "Acme", "ten_acme", RegistrationMode.OPEN);
        when(tenantResolver.resolveTenantId("client-1")).thenReturn(Optional.of(tenantId));
        when(brandingService.resolveForTenant(tenantId)).thenReturn(tenantBranding);

        mockMvc.perform(get("/branding").param("client_id", "client-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("Acme"));
    }
}
