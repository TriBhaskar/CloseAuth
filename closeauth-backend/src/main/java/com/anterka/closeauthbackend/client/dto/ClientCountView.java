package com.anterka.closeauthbackend.client.dto;

/**
 * FE-4d: the console overview's Clients count tile. The smallest possible slice of the still-blocked FE-4.10
 * list gap — a number, never row data or secrets.
 */
public record ClientCountView(long count) {
}
