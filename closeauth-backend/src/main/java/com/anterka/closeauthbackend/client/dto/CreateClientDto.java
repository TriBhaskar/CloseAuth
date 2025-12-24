package com.anterka.closeauthbackend.client.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Set;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class CreateClientDto {
    private String clientId;
    private String clientSecret;
    private Set<String> authenticationMethods; // Changed to String
    private Set<String> authorizationGrantTypes; // Changed to String
    private Set<String> redirectUris;
    private Set<String> scopes;
    private boolean requireProofKey;
}
