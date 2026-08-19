package com.anterka.closeauthbackend.auth.web;

import com.anterka.closeauthbackend.auth.service.AuthFlowTenantResolver;
import com.anterka.closeauthbackend.auth.service.EmailVerificationService;
import com.anterka.closeauthbackend.auth.service.EmailVerificationService.VerificationOutcome;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc unit test for {@link EmailVerificationController} (same convention as
 * {@link LoginControllerTest}/{@link MagicLinkControllerTest}) — none existed before FE-2d. Proves the new HTTP-layer
 * mapping at the confirm endpoint: {@code VERIFIED}/{@code ALREADY_USED} both 200 (spec §6.2.4: already-used reads
 * as success), {@code EXPIRED} is its own 410, everything else stays the generic 400/429 it always was.
 */
class EmailVerificationControllerTest {

    private AuthFlowTenantResolver tenantResolver;
    private EmailVerificationService emailVerificationService;
    private UserService userService;
    private MockMvc mockMvc;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tenantResolver = Mockito.mock(AuthFlowTenantResolver.class);
        emailVerificationService = Mockito.mock(EmailVerificationService.class);
        userService = Mockito.mock(UserService.class);
        EmailVerificationController controller =
                new EmailVerificationController(tenantResolver, emailVerificationService, userService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(tenantResolver.resolveTenantId("client-1")).thenReturn(Optional.of(tenantId));
        when(tenantResolver.resolveTenantSlug("client-1")).thenReturn(Optional.of("ten_acme"));
    }

    @Test
    void verifiedIs200() throws Exception {
        when(emailVerificationService.verify(any(), eq("a@x.com"), eq("123456"))).thenReturn(VerificationOutcome.VERIFIED);

        mockMvc.perform(post("/verify-email/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("code", "123456")
                        .param("client_id", "client-1"))
                .andExpect(status().isOk());
    }

    @Test
    void alreadyUsedIsTheSame200AsVerified() throws Exception {
        when(emailVerificationService.verify(any(), eq("a@x.com"), eq("123456")))
                .thenReturn(VerificationOutcome.ALREADY_USED);

        mockMvc.perform(post("/verify-email/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("code", "123456")
                        .param("client_id", "client-1"))
                .andExpect(status().isOk());
    }

    @Test
    void expiredIsItsOwn410() throws Exception {
        when(emailVerificationService.verify(any(), eq("a@x.com"), eq("stale")))
                .thenReturn(VerificationOutcome.EXPIRED);

        mockMvc.perform(post("/verify-email/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("code", "stale")
                        .param("client_id", "client-1"))
                .andExpect(status().isGone());
    }

    @Test
    void rateLimitedIs429() throws Exception {
        when(emailVerificationService.verify(any(), eq("a@x.com"), eq("123456")))
                .thenReturn(VerificationOutcome.RATE_LIMITED);

        mockMvc.perform(post("/verify-email/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("code", "123456")
                        .param("client_id", "client-1"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void invalidIsTheGeneric400() throws Exception {
        when(emailVerificationService.verify(any(), eq("a@x.com"), eq("wrong")))
                .thenReturn(VerificationOutcome.INVALID);

        mockMvc.perform(post("/verify-email/confirm")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("code", "wrong")
                        .param("client_id", "client-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requestVerificationResolvesTenantSlugAndPassesItThrough() throws Exception {
        when(userService.existsByEmail(any(), eq("a@x.com"))).thenReturn(true);
        when(userService.getUserByEmail(any(), eq("a@x.com"))).thenReturn(
                new UserView(userId, tenantId, "a@x.com", false, null, false, "F", "L", UserStatus.PENDING,
                        null, Instant.now(), Instant.now(), null, null));

        mockMvc.perform(post("/verify-email/request")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "a@x.com")
                        .param("client_id", "client-1"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(emailVerificationService)
                .requestVerification(any(), eq(userId), eq("a@x.com"), eq("client-1"), eq("ten_acme"));
    }
}
