package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.client.dto.ClientCreatedView;
import com.anterka.closeauthbackend.client.dto.ClientView;
import com.anterka.closeauthbackend.client.dto.RegisterClientCommand;
import com.anterka.closeauthbackend.client.service.ClientRegistrationService;
import com.anterka.closeauthbackend.client.service.CloseAuthClientSettings;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.security.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant-scoped client registration (§7.8). {@link RequiresTenantAccess} gate. Wraps {@link ClientRegistrationService}
 * (3c-i/4a); {@code POST} also triggers the 1:1 resource-server auto-create.
 *
 * <p><b>Secret handling:</b> the create response ({@link ClientCreatedView}) is the ONLY place the client secret
 * appears — returned once; only the hash is stored; {@code GET} never returns it.
 *
 * <p><b>Flagged gap (STAGE_7B_REPORT.md):</b> list / update / delete of clients are NOT implemented — SAS's
 * {@code RegisteredClientRepository} exposes no tenant-scoped list or delete, so those need custom JDBC over the
 * SAS-managed {@code oauth2_registered_client} table (significant new logic — flagged, not silently built).
 *
 * <h2>HTTP contract</h2>
 * {@code POST /v1/tenants/{tenantId}/clients} (create, 201, secret once) · {@code GET .../clients/{clientId}} (no secret).
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}/clients")
public class TenantClientController {

    private final ClientRegistrationService clientRegistrationService;
    private final RegisteredClientRepository registeredClientRepository;

    @PostMapping
    public ResponseEntity<ClientCreatedView> create(@PathVariable String tenantId,
                                                    @Valid @RequestBody RegisterClientCommand command) {
        ClientView view = clientRegistrationService.registerClient(ctx(tenantId), command);
        // The plaintext secret is returned ONCE here (only the hash is persisted); never surfaced by GET.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ClientCreatedView(view, command.clientSecret()));
    }

    @GetMapping("/{clientId}")
    public ClientView get(@PathVariable String tenantId, @PathVariable String clientId) {
        RegisteredClient client = registeredClientRepository.findById(clientId);
        // Tenant-scoping defense in depth: a client not owned by this tenant is a 404 (never cross-tenant readable).
        if (client == null || !UUID.fromString(tenantId).equals(CloseAuthClientSettings.getTenantId(client))) {
            throw new CloseAuthDomainException(ErrorCategory.NOT_FOUND, "client.not_found", "Client not found");
        }
        return ClientView.from(client); // no secret
    }

    private TenantContext ctx(String tenantId) {
        return TenantContext.of(UUID.fromString(tenantId));
    }
}
