package com.anterka.closeauthbackend.common.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * Response DTO for the BFF configuration sync endpoint.
 *
 * The BFF fetches this at startup to dynamically configure itself,
 * eliminating hardcoded values and ensuring both services stay in sync.
 *
 * This is a public endpoint — called before the BFF has a token.
 */
@Getter
@Builder
public class BffConfigResponse {

    private VersionInfo version;
    private SessionConfig session;
    private SecurityConfig security;
    private OtpConfig otp;
    private EndpointsConfig endpoints;
    private RegistrationConfig registration;
    private FeaturesConfig features;

    @Getter
    @Builder
    public static class VersionInfo {
        /** API version prefix (e.g., "v1") */
        private String api;
        /** Server version for display/logging */
        private String server;
        /** Minimum BFF version required — BFF should fail-fast if incompatible */
        private String minBffVersion;
    }

    @Getter
    @Builder
    public static class SessionConfig {
        /** Spring session timeout in seconds */
        private int timeoutSeconds;
        /** Recommended OAuth context cookie TTL in seconds */
        private int oauthContextTtlSeconds;
    }

    @Getter
    @Builder
    public static class SecurityConfig {
        private int maxLoginAttempts;
        private int lockoutDurationMinutes;
    }

    @Getter
    @Builder
    public static class OtpConfig {
        private int length;
        private long validitySeconds;
        private int resendRateLimit;
    }

    @Getter
    @Builder
    public static class EndpointsConfig {
        /** URL path that Spring processes form-login POSTs on (e.g., "/login") */
        private String loginProcessingUrl;
        /** URL path for consent submission (e.g., "/oauth2/authorize") */
        private String consentSubmitUrl;
        /** Server context path (empty string if none) */
        private String contextPath;
        /** API version prefix (e.g., "/api/v1") */
        private String apiPrefix;
        /** Client info endpoint path (e.g., "/oauth2/client-info") */
        private String clientInfoUrl;
        /** User registration base path (e.g., "/oauth2/register") */
        private String registerUserUrl;
        /** Admin auth base path (e.g., "/api/v1/admin/auth") */
        private String adminAuthBase;
        /** Client configuration base path (e.g., "/api/v1/clients") */
        private String clientConfigBase;
    }

    @Getter
    @Builder
    public static class RegistrationConfig {
        private int cacheTtlHours;
        private int adminPendingTtlDays;
    }

    @Getter
    @Builder
    public static class FeaturesConfig {
        /** Whether OIDC dynamic client registration is enabled */
        private boolean dynamicClientRegistration;
    }
}


