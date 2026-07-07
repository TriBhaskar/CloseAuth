package com.anterka.closeauthbackend.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read view of an outstanding invitation (§7.8 invite issuance). The raw invite secret is delivered only by email and
 * is NEVER returned here (it is the credential). Output DTO.
 */
public record InviteView(UUID id, String email, Instant expiresAt, Instant createdAt) {
}
