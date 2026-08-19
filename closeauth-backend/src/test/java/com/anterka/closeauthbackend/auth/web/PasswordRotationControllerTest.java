package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService.RotationOutcome;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService.RotationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-free unit test for {@link PasswordRotationController} (standalone MockMvc — no Spring context, no security
 * filter chain; this codebase has no existing {@code @WebMvcTest} convention, so a full slice test would be
 * inventing new infrastructure for one endpoint). Covers what's specific to the web layer: the {@code authorize_query}
 * CR/LF-and-length guard (Finding 6 — must be a 400, not a 500 from {@code URI.create}, and must reject BEFORE the
 * one-time token is consumed) and that a successful confirm never appears as a 401/JSON body like the login
 * endpoints' uniform failure (this endpoint's failure shape is a bare 400, matching {@code PasswordResetController}).
 */
class PasswordRotationControllerTest {

    private AuthFlowTenantResolver tenantResolver;
    private PasswordRotationService rotationService;
    private LoginSuccessResponder loginSuccessResponder;
    private MockMvc mockMvc;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantResolver = Mockito.mock(AuthFlowTenantResolver.class);
        rotationService = Mockito.mock(PasswordRotationService.class);
        loginSuccessResponder = Mockito.mock(LoginSuccessResponder.class);
        PasswordRotationController controller =
                new PasswordRotationController(tenantResolver, rotationService, loginSuccessResponder);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(tenantResolver.resolveTenantId("client-1")).thenReturn(Optional.of(tenantId));
    }

    @Test
    void malformedAuthorizeQueryIsRejectedBeforeTheTokenIsEverConsumed() throws Exception {
        mockMvc.perform(post("/password-rotation/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "tok")
                        .param("password", "new-password-123")
                        .param("client_id", "client-1")
                        .param("authorize_query", "scope=openid\r\nSet-Cookie: evil=1"))
                .andExpect(status().isBadRequest());

        verify(rotationService, never()).completeRotation(any(), any(), any());
    }

    @Test
    void oversizedAuthorizeQueryIsRejected() throws Exception {
        String huge = "a".repeat(5000);

        mockMvc.perform(post("/password-rotation/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "tok")
                        .param("password", "new-password-123")
                        .param("client_id", "client-1")
                        .param("authorize_query", huge))
                .andExpect(status().isBadRequest());

        verify(rotationService, never()).completeRotation(any(), any(), any());
    }

    @Test
    void unresolvableClientIsRejectedWithoutConsultingTheService() throws Exception {
        when(tenantResolver.resolveTenantId("unknown-client")).thenReturn(Optional.empty());

        mockMvc.perform(post("/password-rotation/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "tok")
                        .param("password", "new-password-123")
                        .param("client_id", "unknown-client"))
                .andExpect(status().isBadRequest());

        verify(rotationService, never()).completeRotation(any(), any(), any());
    }

    @Test
    void invalidTokenIsAGenericBadRequestNoEnumeration() throws Exception {
        when(rotationService.completeRotation(any(), eq("bad-tok"), any()))
                .thenReturn(new RotationResult(RotationOutcome.INVALID, null));

        mockMvc.perform(post("/password-rotation/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "bad-tok")
                        .param("password", "new-password-123")
                        .param("client_id", "client-1"))
                .andExpect(status().isBadRequest());
    }

    // FE-2.5 (spec §6.2.7): the literal security-property proof — "no route,
    // no store state, and no BFF response in this flow exposes a session or
    // token before confirm succeeds." A failed confirm must never carry a
    // Set-Cookie, and the SAME loginSuccessResponder that establishes the
    // session on success (see the test below) must never even be invoked.
    @Test
    void invalidTokenNeverEstablishesASessionOrSetsACookie() throws Exception {
        when(rotationService.completeRotation(any(), eq("bad-tok"), any()))
                .thenReturn(new RotationResult(RotationOutcome.INVALID, null));

        mockMvc.perform(post("/password-rotation/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "bad-tok")
                        .param("password", "new-password-123")
                        .param("client_id", "client-1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));

        verify(loginSuccessResponder, never())
                .establishSessionAndResolveRedirect(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void successEstablishesTheSessionAndRedirectsToTheResumeUrl() throws Exception {
        when(rotationService.completeRotation(any(), eq("good-tok"), any()))
                .thenReturn(new RotationResult(RotationOutcome.ROTATED, userId));
        when(loginSuccessResponder.establishSessionAndResolveRedirect(
                any(), any(), eq(tenantId), eq(userId), eq("pwd"), eq(false), eq("scope=openid&state=abc")))
                .thenReturn("https://issuer.example/oauth2/authorize?scope=openid&state=abc");

        mockMvc.perform(post("/password-rotation/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "good-tok")
                        .param("password", "new-password-123")
                        .param("client_id", "client-1")
                        .param("authorize_query", "scope=openid&state=abc"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://issuer.example/oauth2/authorize?scope=openid&state=abc"));
    }
}
