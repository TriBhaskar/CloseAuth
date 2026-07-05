package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the tenant for an interactive auth flow from the authorization request's {@code client_id} (Stage 6a).
 *
 * <p><b>This is the spine of tenant-correctness for authentication.</b> Per 4a, a client belongs to exactly one
 * tenant (its {@code tenant_id}, carried in {@link com.anterka.closeauthbackend.client.service.CloseAuthClientSettings}).
 * The login and {@code /authorize} flows use the resolved tenant for: which user pool {@code verifyPassword}
 * authenticates against (per-tenant email uniqueness — the same email in different tenants is different users), which
 * tenant a new session is scoped to, and which tenant the SSO session consult validates against. A bug here —
 * resolving the wrong tenant, or not scoping to it — would be a cross-tenant authentication vulnerability, so the
 * resolution is explicit and single-sourced here.
 */
@Service
@RequiredArgsConstructor
public class AuthFlowTenantResolver {

    private final RegisteredClientRepository registeredClientRepository;

    /** The registered client for a public {@code client_id}, or empty if unknown. */
    public Optional<RegisteredClient> findClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registeredClientRepository.findByClientId(clientId));
    }

    /** The tenant owning the given {@code client_id}, or empty if the client is unknown / carries no tenant. */
    public Optional<UUID> resolveTenantId(String clientId) {
        return findClient(clientId).map(CloseAuthClientSettings::getTenantId);
    }
}
