package com.anterka.closeauthbackend.oauth2.controller;

import com.anterka.closeauthbackend.oauth2.dto.ClientInfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller handling OAuth2 flow related endpoints:
 * - Client info endpoint for BFF to display on login/consent pages
 * - Consent page redirect to BFF application
 */
@Slf4j
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
public class OAuth2FlowController {

    private final RegisteredClientRepository registeredClientRepository;

    @Value("${closeauth.bff.consent-page}")
    private String consentPageUrl;

    /**
     * Returns public client information for display on login/consent pages.
     * This endpoint is public and can be called by BFF without authentication.
     *
     * @param clientId The OAuth2 client ID
     * @return Client display information (name, logo, scopes)
     */
    @GetMapping("/client-info")
    public ResponseEntity<ClientInfoResponse> getClientInfo(@RequestParam("client_id") String clientId) {
        log.debug("Fetching client info for client_id: {}", clientId);

        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        if (client == null) {
            log.warn("Client not found for client_id: {}", clientId);
            return ResponseEntity.notFound().build();
        }

        // Extract logo_uri from client settings (custom metadata)
        String logoUri = client.getClientSettings().getSetting("logo_uri");

        ClientInfoResponse response = ClientInfoResponse.builder()
                .clientId(client.getClientId())
                .clientName(client.getClientName())
                .logoUri(logoUri)
                .scopes(client.getScopes())
                .build();

        log.debug("Returning client info for {}: {}", clientId, response);
        return ResponseEntity.ok(response);
    }

    /**
     * Handles the consent page redirect.
     * When Spring Authorization Server determines consent is required,
     * it redirects to this endpoint. This controller then redirects to
     * the BFF consent page with all necessary parameters.
     *
     * @param principal  The authenticated user
     * @param clientId   The OAuth2 client ID requesting authorization
     * @param scope      Space-separated list of requested scopes
     * @param state      OAuth2 state parameter (must be returned in consent POST)
     * @return RedirectView to BFF consent page with query parameters
     */
//    @GetMapping("/consent")
//    public RedirectView consent(
//            Principal principal,
//            @RequestParam("client_id") String clientId,
//            @RequestParam("scope") String scope,
//            @RequestParam("state") String state) {
//
//        log.debug("Consent requested for client_id: {}, scope: {}, principal: {}",
//                clientId, scope, principal.getName());
//
//        // Fetch client name for display on consent page
//        String clientName = clientId; // Default to clientId if client not found
//        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
//        if (client != null) {
//            clientName = client.getClientName();
//        }
//
//        // Build redirect URL with query parameters
//        String redirectUrl = UriComponentsBuilder.fromUriString(consentPageUrl)
//                .queryParam("client_id", clientId)
//                .queryParam("scope", scope)
//                .queryParam("state", state)
//                .queryParam("principal", principal.getName())
//                .queryParam("client_name", clientName)
//                .build()
//                .toUriString();
//
//        log.debug("Redirecting to BFF consent page: {}", redirectUrl);
//        return new RedirectView(redirectUrl);
//    }
}

