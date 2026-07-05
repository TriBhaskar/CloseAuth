package com.anterka.closeauthbackend.auth.dto;

import java.util.List;

/**
 * The consent-page context the hosted UI renders (Stage 6b-ii). Returned by {@code GET /oauth2/consent}. The UI shows
 * {@code clientName} requesting {@code scopes} (with descriptions), pre-checks the {@code alreadyGranted} and
 * non-{@code requiresConsent} scopes, and POSTs the user's decision back to {@code /oauth2/authorize}
 * (form: {@code client_id}, {@code state}, and {@code scope} per approved scope) — or denies (SAS returns
 * {@code access_denied}).
 *
 * @param clientId       the OAuth client id (echoed for the POST-back)
 * @param clientName     display name of the requesting client
 * @param state          the SAS state (echoed for the POST-back)
 * @param scopes         requested scopes with descriptions + requires-consent flags
 * @param alreadyGranted scopes this user previously consented to for this client (pre-checked; not re-prompted)
 */
public record ConsentContext(
        String clientId,
        String clientName,
        String state,
        List<ConsentScopeView> scopes,
        List<String> alreadyGranted) {
}
