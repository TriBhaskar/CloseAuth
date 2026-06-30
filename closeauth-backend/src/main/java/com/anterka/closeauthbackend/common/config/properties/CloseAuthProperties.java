package com.anterka.closeauthbackend.common.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Centralized configuration properties for CloseAuth.
 * All configurable values are defined here instead of being hardcoded.
 */
@Component
@ConfigurationProperties(prefix = "closeauth")
@Getter
@Setter
public class CloseAuthProperties {

    private String issuerUrl = "http://localhost:9088";

    private Keys keys = new Keys();
    private Security security = new Security();
    private Otp otp = new Otp();
    private Registration registration = new Registration();
    private Cors cors = new Cors();
    private Bff bff = new Bff();
    private Bootstrap bootstrap = new Bootstrap();

    @Getter
    @Setter
    public static class Keys {
        private RSAPublicKey rsaPublicKey;
        private RSAPrivateKey rsaPrivateKey;
    }

    @Getter
    @Setter
    public static class Security {
        private int maxLoginAttempts = 5;
        private int lockoutDurationMinutes = 30;

        /**
         * IP addresses of trusted reverse proxies / BFF instances. The
         * {@code X-Forwarded-For} header is only honored when the direct caller
         * ({@code request.getRemoteAddr()}) is in this list. When empty, the raw
         * socket address is always used, preventing clients from spoofing their IP
         * to bypass IP-based rate limiting.
         */
        private List<String> trustedProxies = List.of();
    }

    @Getter
    @Setter
    public static class Otp {
        private int length = 6;
        private long validitySeconds = 600; // 10 minutes
        private int resendRateLimit = 3;
    }

    @Getter
    @Setter
    public static class Registration {
        private int cacheTtlHours = 2;
        private int adminPendingTtlDays = 7;
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:5173");
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        private List<String> allowedHeaders = List.of("Authorization", "X-User-Token", "Content-Type");
        private boolean allowCredentials = true;
    }

    @Getter
    @Setter
    public static class Bff {
        /**
         * Base URL of the BFF server (e.g., http://localhost:8080).
         * login-page and consent-page are derived from this in application.properties.
         */
        private String baseUrl = "http://localhost:8080";
        private String loginPage;
        private String consentPage;

        /**
         * Session timeout in seconds. Must match or exceed oauthContextTtlSeconds.
         * BFF uses this to align its cookie TTLs with Spring's session timeout.
         */
        private int sessionTimeoutSeconds = 900;

        /**
         * OAuth context cookie TTL in seconds.
         * This is the maximum time a user has to complete the login+consent flow.
         */
        private int oauthContextTtlSeconds = 600;
    }

    /**
     * Settings for the bootstrap (seed) OAuth2 client created at startup by
     * {@code DefaultClientInitializer}. The secret MUST be provided externally
     * (env/secret manager) in any non-local environment, and seeding should be
     * disabled once the client exists in a managed datastore.
     */
    @Getter
    @Setter
    public static class Bootstrap {
        /**
         * Whether to create the default admin client on startup if it is missing.
         * Set to {@code false} in production once the client is provisioned.
         */
        private boolean enabled = true;

        private String clientId = "admin-client";

        /**
         * Plaintext secret for the bootstrap client. When blank, seeding is skipped
         * (no client with a default/guessable secret is ever created).
         */
        private String clientSecret = "";

        private String redirectUri = "http://localhost:8080/login/oauth2/code/admin-client";

        private List<String> scopes = List.of("read", "write", "client.create");
    }
}

