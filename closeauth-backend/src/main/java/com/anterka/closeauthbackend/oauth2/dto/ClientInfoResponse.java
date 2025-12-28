package com.anterka.closeauthbackend.oauth2.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * DTO for returning client display information to BFF for login/consent pages.
 * Contains only public, non-sensitive client metadata.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClientInfoResponse {

    /**
     * The client ID (public identifier)
     */
    private String clientId;

    /**
     * Human-readable client name for display
     */
    private String clientName;

    /**
     * Logo URI for the client application (optional)
     */
    private String logoUri;

    /**
     * Set of scopes the client is allowed to request
     */
    private Set<String> scopes;
}

