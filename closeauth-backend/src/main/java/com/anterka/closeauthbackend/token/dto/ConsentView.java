package com.anterka.closeauthbackend.token.dto;

import java.util.List;

/**
 * Read view of a user's granted OAuth2 consent for one client (§7.8 consent management). {@code registeredClientId} is
 * SAS's internal client id (the handle for revocation); {@code clientId} is the human client identifier for display;
 * {@code scopes} are the consented authorities. Output DTO — no principal/tenant internals.
 */
public record ConsentView(String registeredClientId, String clientId, List<String> scopes) {
}
