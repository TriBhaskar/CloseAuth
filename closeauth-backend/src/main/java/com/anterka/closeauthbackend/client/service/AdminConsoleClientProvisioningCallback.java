package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.tenant.entity.Tenant;
import com.anterka.closeauthbackend.tenant.repository.TenantRepository;
import com.anterka.closeauthbackend.tenant.service.TenantProvisioningCallback;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Auto-provisions the deterministic, PUBLIC {@code admin-console-{tenantSlug}} OAuth2 client at tenant creation, via
 * the {@link TenantProvisioningCallback} seam ("Option A" design) — so the (future, UI-3) tenant-admin dashboard
 * always has a real, predictable client to authenticate against, with no manual registration step and no client
 * secret anywhere (public client, PKCE-required, relies on silent SSO re-authorization for session longevity
 * instead of a {@code refresh_token} grant).
 *
 * <p><b>Why this does NOT call {@link ClientRegistrationService#registerClient}</b> (verified by reading the source,
 * not assumed): that method's very first line after validation is
 * {@code tenantService.requireActiveTenant(context)}, and the tenant is still {@code PROVISIONING} at the point any
 * {@code TenantProvisioningCallback} runs (it fires inside {@code TenantService.provisionTenant}, before
 * {@code activateTenant} is ever called) — {@code requireActiveTenant} would throw {@code TenantSuspendedException}
 * on literally every tenant provisioned. This is exactly the same reason {@code BrandingProvisioningCallback},
 * {@code RegistrationConfigProvisioningCallback}, and {@code RoleStarterPackProvisioningCallback} all bypass their
 * normal service and persist directly via a repository — this callback follows that same established pattern,
 * building the {@link RegisteredClient} directly (mirroring {@code ClientRegistrationService.buildRegisteredClient}
 * minus the active-tenant guard) and saving it through the same tenant-aware {@link RegisteredClientRepository}
 * bean, which is what actually writes the {@code tenant_id} column (see {@link CloseAuthClientSettings}).
 *
 * <p><b>No auto-created Resource Server, by necessity as well as by choice:</b>
 * {@code ResourceServerService.autoCreateForClient} — the call {@code registerClient} makes unconditionally after
 * persisting a client — ALSO starts with {@code tenantService.requireActiveTenant(context)}, so it could not be
 * invoked here either without duplicating its own guard-bypass. Rather than doing that, this callback skips RS
 * auto-creation entirely: this client protects no API of its own (it is purely a consumer of the existing,
 * platform-owned admin API; authorization for it comes from the {@code tenant_roles} claim via
 * {@code @RequiresTenantAccess}, not from an RS-scoped OAuth2 scope grant), so there is nothing for an inert,
 * auto-created RS to usefully protect. Skipping it is both the simpler outcome and the only one available without
 * extra duplicated code — no separate "harmless unused RS" is created for this client.
 *
 * <p>Runs inside the tenant-provisioning transaction, like the other provisioning callbacks: a failure here rolls
 * back the whole tenant creation.
 *
 * <p><b>Flush-before-raw-JDBC-insert:</b> {@code TenantService.provisionTenant} persists the {@code Tenant} via
 * Hibernate (deferred write-behind — the physical {@code INSERT} isn't necessarily sent yet when this callback
 * runs), but {@link TenantAwareRegisteredClientRepository#save} inserts via a raw {@code JdbcTemplate} statement on
 * the SAME connection/transaction, executed immediately. Without an explicit flush, that raw INSERT can run before
 * the tenant row is physically visible and trip the {@code oauth2_registered_client_tenant_id_fkey} FK constraint
 * (confirmed by running this exact failure against live Postgres). The other provisioning callbacks never hit this
 * because they, too, persist via plain JPA repositories in the SAME Hibernate session, so Hibernate's own flush
 * ordering keeps them consistent; this callback is the first to cross from JPA into raw JDBC inside the same
 * transaction, so it must force the flush itself.
 */
@Component
@RequiredArgsConstructor
public class AdminConsoleClientProvisioningCallback implements TenantProvisioningCallback {

    private static final String CLIENT_ID_PREFIX = "admin-console-";

    private final RegisteredClientRepository registeredClientRepository;
    private final TenantRepository tenantRepository;
    private final CloseAuthProperties properties;

    @Override
    public void onTenantProvisioned(Tenant tenant, TenantContext context) {
        // Force the pending Tenant INSERT to hit the DB now, before the raw-JDBC client insert below — see class
        // javadoc "Flush-before-raw-JDBC-insert".
        tenantRepository.flush();

        ClientSettings.Builder clientSettings = ClientSettings.builder()
                .requireProofKey(true)              // PKCE required — mandatory platform default for public clients
                .requireAuthorizationConsent(false); // trusted: skip consent for a tenant admin on their own tenant
        CloseAuthClientSettings.withTenantId(clientSettings, context.tenantId());

        RegisteredClient registeredClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID_PREFIX + tenant.getSlug())
                .clientName("Admin Console — " + tenant.getName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE) // public: no secret, ever
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE) // ONLY grant — no refresh_token
                .redirectUri(properties.getBff().getAdminCallback())
                .scope("openid")
                .scope("profile")
                .clientSettings(clientSettings.build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.getToken().getAccessTokenTtl())
                        .build())
                .build();

        registeredClientRepository.save(registeredClient);
    }
}
