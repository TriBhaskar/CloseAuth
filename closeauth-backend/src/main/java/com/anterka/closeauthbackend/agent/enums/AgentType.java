package com.anterka.closeauthbackend.agent.enums;

/**
 * AI agent kinds (Section 7.10). Values must match the CHECK constraint on
 * {@code agents.agent_type} exactly.
 */
public enum AgentType {
    MCP_SERVER,
    EMBEDDED_AGENT,
    WORKFLOW_RUNNER
}
