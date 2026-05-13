package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.client.repository.ClientOwnershipRepository;
import com.anterka.closeauthbackend.common.exception.ClientOwnershipException;
import com.anterka.closeauthbackend.user.security.UserContextHelper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared component for verifying client ownership.
 * Eliminates duplicated verifyClientOwnership/getCurrentUserId methods across services.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientOwnershipVerifier {

    private final ClientOwnershipRepository clientOwnershipRepository;

    /**
     * Verifies that the current user (from X-User-Token) owns the specified client.
     *
     * @param clientId the OAuth2 client ID
     * @param request  the HTTP request containing user attributes
     * @throws ClientOwnershipException if the user does not own the client
     */
    public void verify(String clientId, HttpServletRequest request) {
        Integer userId = getUserId(request);
        if (!clientOwnershipRepository.existsByClient_IdAndUser_Id(clientId, userId)) {
            log.warn("User {} attempted to access client {} without ownership", userId, clientId);
            throw new ClientOwnershipException("You do not have permission to modify this client");
        }
    }

    /**
     * Extracts the user ID from request attributes set by TwoLayerAuthenticationFilter.
     */
    public Integer getUserId(HttpServletRequest request) {
        return UserContextHelper.getUserId(request);
    }
}

