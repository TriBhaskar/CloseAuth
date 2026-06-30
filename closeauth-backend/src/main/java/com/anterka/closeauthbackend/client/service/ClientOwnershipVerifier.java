package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.client.repository.ClientOwnershipRepository;
import com.anterka.closeauthbackend.common.exception.ClientOwnershipException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Shared component for verifying client ownership.
 * Works purely on the resolved user id so the service layer stays free of web types.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClientOwnershipVerifier {

    private final ClientOwnershipRepository clientOwnershipRepository;

    /**
     * Verifies that the given user owns the specified client.
     *
     * @param clientId the OAuth2 client ID
     * @param userId   the authenticated admin user id (from {@code UserActionContext})
     * @throws ClientOwnershipException if the user does not own the client
     */
    public void verify(String clientId, Integer userId) {
        if (!clientOwnershipRepository.existsByClient_IdAndUser_Id(clientId, userId)) {
            log.warn("User {} attempted to access client {} without ownership", userId, clientId);
            throw new ClientOwnershipException("You do not have permission to modify this client");
        }
    }
}
