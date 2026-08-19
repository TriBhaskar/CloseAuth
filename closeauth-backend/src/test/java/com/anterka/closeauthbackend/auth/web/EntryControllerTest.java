package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.tenant.dto.EntryResolutionView;
import com.anterka.closeauthbackend.tenant.enums.TenantStatus;
import com.anterka.closeauthbackend.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc unit test for {@link EntryController} (same "no {@code @WebMvcTest}
 * convention exists here" reasoning as {@code PasswordRotationControllerTest}). THE test that
 * matters most: an unknown tenant, a non-ACTIVE tenant, and a malformed {@code tenantId} must be
 * byte-identical 404s — spec §6.1's "must not be distinguishable" requirement, proven at the HTTP
 * layer, not just asserted at the service layer.
 */
class EntryControllerTest {

    private TenantService tenantService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tenantService = Mockito.mock(TenantService.class);
        EntryController controller = new EntryController(tenantService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void anActiveTenantResolvesToTheFullView() throws Exception {
        when(tenantService.resolveActiveTenantBySlug("ten_acme-inc"))
                .thenReturn(Optional.of(new EntryResolutionView("ten_acme-inc", "Acme Corp", TenantStatus.ACTIVE)));

        mockMvc.perform(get("/entry/resolve").param("tenantId", "ten_acme-inc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("ten_acme-inc"))
                .andExpect(jsonPath("$.displayName").value("Acme Corp"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void anUnknownTenantIs404WithAnEmptyBody() throws Exception {
        when(tenantService.resolveActiveTenantBySlug("ten_does-not-exist")).thenReturn(Optional.empty());

        mockMvc.perform(get("/entry/resolve").param("tenantId", "ten_does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    void aNonActiveTenantIs404WithTheSameEmptyBodyAsUnknown() throws Exception {
        // The service layer already collapses this (TenantServiceTest); here it matters that
        // the controller doesn't reintroduce a distinction on the way out.
        when(tenantService.resolveActiveTenantBySlug("ten_suspended-co")).thenReturn(Optional.empty());

        mockMvc.perform(get("/entry/resolve").param("tenantId", "ten_suspended-co"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "acme-inc",              // missing ten_ prefix
            "ten_",                  // empty body
            "ten_A",                 // uppercase not allowed
            "ten_a",                 // body too short (regex requires 2+ chars after the first)
            "ten_../../etc/passwd",  // injection-shaped
            "",
    })
    void aMalformedTenantIdIs404WithoutEverQueryingTheDatabase(String malformed) throws Exception {
        mockMvc.perform(get("/entry/resolve").param("tenantId", malformed))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));

        verify(tenantService, never()).resolveActiveTenantBySlug(any());
    }
}
