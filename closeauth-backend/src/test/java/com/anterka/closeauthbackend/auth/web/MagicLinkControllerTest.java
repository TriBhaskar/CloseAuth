package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.MagicLinkService;
import com.anterka.closeauthbackend.auth.service.MagicLinkService.MagicLinkAuthResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc unit test for {@link MagicLinkController} (same convention as
 * {@link LoginControllerTest}/{@link PasswordRotationControllerTest}) — none existed before FE-2b. Closes FE-2.5's
 * (spec §6.2.7) "including on the magic-link path" clause: a denied consume — which {@link MagicLinkService#consume}
 * already returns uniformly for a suspended user/tenant AND a pending/expired forced rotation via
 * {@code LoginPolicyService.isLoginAllowed} — must never establish a session. Does NOT test that a rotation-pending
 * magic-link user gets routed into the rotation interstitial (unlike password login) — that UX gap is deliberately
 * FE-2.8's job (build plan), not FE-2.5's; this test only proves the security property already holds.
 */
class MagicLinkControllerTest {

    private AuthFlowTenantResolver tenantResolver;
    private MagicLinkService magicLinkService;
    private LoginSuccessResponder loginSuccessResponder;
    private MockMvc mockMvc;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantResolver = Mockito.mock(AuthFlowTenantResolver.class);
        magicLinkService = Mockito.mock(MagicLinkService.class);
        loginSuccessResponder = Mockito.mock(LoginSuccessResponder.class);
        MagicLinkController controller = new MagicLinkController(tenantResolver, magicLinkService, loginSuccessResponder);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(tenantResolver.resolveTenantId("client-1")).thenReturn(Optional.of(tenantId));
    }

    @Test
    void aDeniedConsumeNeverEstablishesASessionOrSetsACookie() throws Exception {
        // Covers every denial reason MagicLinkService.consume collapses into "authenticated=false"
        // uniformly (invalid/expired token, suspended user/tenant, or a pending/expired forced
        // rotation) — the controller must react identically to all of them.
        when(magicLinkService.consume(eq("bad-tok"), eq(tenantId)))
                .thenReturn(new MagicLinkAuthResult(false, null, tenantId));

        mockMvc.perform(get("/magic-link/consume").param("token", "bad-tok").param("client_id", "client-1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verify(loginSuccessResponder, never())
                .establishSessionAndResolveRedirect(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void anUnresolvableClientIdIsRejectedWithoutConsultingTheService() throws Exception {
        when(tenantResolver.resolveTenantId("unknown-client")).thenReturn(Optional.empty());

        mockMvc.perform(get("/magic-link/consume").param("token", "tok").param("client_id", "unknown-client"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/login"));

        verify(magicLinkService, never()).consume(any(), any());
    }

    @Test
    void aSuccessfulConsumeEstablishesTheSessionAndRedirectsToTheResumeUrl() throws Exception {
        when(magicLinkService.consume(eq("good-tok"), eq(tenantId)))
                .thenReturn(new MagicLinkAuthResult(true, userId, tenantId));
        when(loginSuccessResponder.establishSessionAndResolveRedirect(
                any(), any(), eq(tenantId), eq(userId), eq("magic_link"), eq(false)))
                .thenReturn("https://issuer.example/oauth2/authorize?resume=1");

        mockMvc.perform(get("/magic-link/consume").param("token", "good-tok").param("client_id", "client-1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://issuer.example/oauth2/authorize?resume=1"));
    }
}
