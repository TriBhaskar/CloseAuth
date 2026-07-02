package com.anterka.closeauthbackend.agent.enums;

/**
 * AI agent lifecycle states (Section 7.10). Values must match the CHECK
 * constraint on {@code agents.status} exactly.
 */
public enum AgentStatus {
    ACTIVE,
    SUSPENDED,
    REVOKED
}
