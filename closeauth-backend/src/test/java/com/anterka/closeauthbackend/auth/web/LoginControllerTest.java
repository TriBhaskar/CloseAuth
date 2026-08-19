package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.dto.LoginOutcome;
import com.anterka.closeauthbackend.auth.dto.LoginOutcome.FailureReason;
import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.LoginPolicyService;
import com.anterka.closeauthbackend.auth.service.PasswordRotationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice-free unit test for {@link LoginController} (standalone MockMvc — same convention as
 * {@link PasswordRotationControllerTest}/{@link EntryControllerTest}). This is FE-2.4's literal acceptance
 * deliverable (build plan: "Acceptance is behavioural, not visual... Written as a test"): proves that unknown email,
 * wrong password, an inactive/pending user, a suspended user, a suspended tenant, and a rate-limited attempt all
 * produce the EXACT same 401 status and JSON body — {@link LoginOutcome}'s own javadoc already states this
 * requirement; this test proves the controller actually honours it at the HTTP layer, not just that
 * {@code LoginPolicyServiceTest} classifies each reason correctly server-side.
 */
class LoginControllerTest {

    private AuthFlowTenantResolver tenantResolver;
    private LoginPolicyService loginPolicyService;
    private LoginSuccessResponder loginSuccessResponder;
    private PasswordRotationService passwordRotationService;
    private MockMvc mockMvc;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantResolver = Mockito.mock(AuthFlowTenantResolver.class);
        loginPolicyService = Mockito.mock(LoginPolicyService.class);
        loginSuccessResponder = Mockito.mock(LoginSuccessResponder.class);
        passwordRotationService = Mockito.mock(PasswordRotationService.class);
        LoginController controller = new LoginController(
                tenantResolver, loginPolicyService, loginSuccessResponder, passwordRotationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(tenantResolver.resolveTenantId("client-1")).thenReturn(Optional.of(tenantId));
    }

    // ---- The core FE-2.4 proof: every FailureReason collapses into the identical response ----------------------

    @ParameterizedTest
    @EnumSource(value = FailureReason.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void everyFailureReasonProducesTheIdenticalUniformResponse(FailureReason reason) throws Exception {
        when(loginPolicyService.authenticate(any(), eq("a@x.com"), any()))
                .thenReturn(LoginOutcome.failure(reason));

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("password", "whatever")
                        .param("client_id", "client-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andExpect(jsonPath("$.error_description").value("Authentication failed."))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verify(loginSuccessResponder, never())
                .establishSessionAndResolveRedirect(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    // ---- An unresolvable client_id takes a different code path but must land on the SAME response ---------------

    @Test
    void anUnresolvableClientIdProducesTheIdenticalUniformResponseWithoutConsultingThePolicy() throws Exception {
        when(tenantResolver.resolveTenantId("unknown-client")).thenReturn(Optional.empty());

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("password", "whatever")
                        .param("client_id", "unknown-client"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
                .andExpect(jsonPath("$.error_description").value("Authentication failed."));

        verify(loginPolicyService, never()).authenticate(any(), any(), any());
    }

    // ---- Sanity: the one legitimate DIFFERENT outcome (rotation required) still routes correctly, unaffected ----

    @Test
    void rotationRequiredRoutesToTheRotationInterstitialNotTheUniformFailure() throws Exception {
        when(loginPolicyService.authenticate(any(), eq("a@x.com"), eq("temp-pw")))
                .thenReturn(LoginOutcome.rotationRequired(userId));
        when(passwordRotationService.beginRotation(any(), eq(userId), eq("a@x.com"), eq("client-1"), any(), any()))
                .thenReturn("https://bff.example/t/acme/password-rotation?token=tok&client_id=client-1");

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("password", "temp-pw")
                        .param("client_id", "client-1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://bff.example/t/acme/password-rotation?token=tok&client_id=client-1"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        verify(loginSuccessResponder, never())
                .establishSessionAndResolveRedirect(any(), any(), any(), any(), any(), anyBoolean(), any());
    }
}
