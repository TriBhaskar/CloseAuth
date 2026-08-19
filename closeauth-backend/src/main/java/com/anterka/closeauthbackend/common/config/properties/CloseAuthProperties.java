package com.anterka.closeauthbackend.common.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
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
    private ResourceServer resourceServer = new ResourceServer();
    private Token token = new Token();
    private Session session = new Session();
    private OneTimeToken oneTimeToken = new OneTimeToken();
    private Branding branding = new Branding();
    private PlatformAdmin platformAdmin = new PlatformAdmin();
    private Audit audit = new Audit();

    /**
     * Audit outbox + retention config (§7.11, Stage 8). Single source for the drain worker's cadence/batching/retry
     * budget and the retention policy. The retention value (90 days) is config-driven so per-tenant retention (Phase 2)
     * has a clean landing spot; automated deletion is opt-in ({@code enabled=false}) — the mechanism ships, but MVP
     * does not silently delete history.
     */
    @Getter
    @Setter
    public static class Audit {
        /** Drain worker poll interval (ms) — how often undrained outbox rows are swept to {@code audit_events}. */
        private long drainIntervalMs = 2000;
        /** Max rows drained per poll (bounds the per-tick work; the backlog just waits for the next tick). */
        private int drainBatchSize = 100;
        /** Retry budget per outbox row before it is dead-lettered (left in place, logged loudly, excluded from drain). */
        private int maxAttempts = 5;
        private Retention retention = new Retention();

        /**
         * Retention policy (§7.11). The {@code days} value is platform-wide in MVP (per-tenant is Phase 2). Deletion is
         * OFF by default: enabling it turns on the scheduled cleanup job that removes {@code audit_events} older than
         * the window (and only those rows — never anything they reference).
         */
        @Getter
        @Setter
        public static class Retention {
            /** Whether the scheduled retention-cleanup job actually deletes. Off in MVP (policy documented, not enforced). */
            private boolean enabled = false;
            /** Hot-retention window in days (default 90, §7.11). Config-driven so per-tenant override lands cleanly later. */
            private int days = 90;
            /** Cron for the cleanup job (default 03:30 daily). Only runs when {@code enabled=true}. */
            private String cleanupCron = "0 30 3 * * *";
        }
    }

    /**
     * Platform-admin bootstrap + token config (§7.8, Stage 7a). The bootstrap credential creates the FIRST platform
     * admin on an empty {@code platform_admins} table (idempotent thereafter); it MUST come from env/secret manager
     * and be rotated immediately after first login (never hardcode; blank → bootstrap is skipped).
     */
    @Getter
    @Setter
    public static class PlatformAdmin {
        /** Email of the initial platform admin (env). Blank → bootstrap skipped. */
        private String bootstrapEmail = "";
        /** Plaintext bootstrap password (env only). Blank → bootstrap skipped. ROTATE after first login. */
        private String bootstrapPassword = "";
        private String bootstrapFirstName = "Platform";
        private String bootstrapLastName = "Admin";
        /**
         * Lifetime of a minted platform-admin access token (no refresh for platform admins in MVP). Kept SHORT (5 min,
         * matching the regular access-token TTL) on purpose: the highest-privilege principal gets the shortest-lived
         * credential, so even if the revocation list is degraded (fail-open) a compromised token self-expires quickly.
         */
        private Duration tokenTtl = Duration.ofMinutes(5);
    }

    /**
     * Platform-default hosted-page branding (§7.1, Stage 6b-ii). Fills null fields when a tenant hasn't set its own
     * branding, so the resolution endpoint always returns renderable values. Sourced here (single place), not scattered.
     */
    @Getter
    @Setter
    public static class Branding {
        private String primaryColor = "#4F46E5";
        private String backgroundColor = "#FFFFFF";
        private String accentColor = "#22D3EE";
        /** Default logo (null → the UI renders no logo / its own placeholder). */
        private String defaultLogoUrl = null;
        /** Fallback company name (null → the UI renders a generic "Sign in"). Never the internal tenant name. */
        private String companyNameFallback = null;
    }

    /**
     * Token lifetimes (§7.3). Single source of truth: used both when configuring a client's {@code TokenSettings}
     * and for the Redis revocation-marker TTL (which must equal the max access-token TTL so a marker outlives the
     * tokens it suppresses). Bound as ISO/Boot durations (e.g. {@code 5m}, {@code 14d}).
     */
    @Getter
    @Setter
    public static class Token {
        private Duration accessTokenTtl = Duration.ofMinutes(5);
        private Duration refreshTokenTtl = Duration.ofDays(14);
    }

    /**
     * Auth Server session timeouts (§7.5). Single source of truth for the tenant-scoped SSO session mechanism
     * ({@code AuthServerSessionService}) — the idle/absolute/remember-me windows and the Redis hot-store key prefix.
     * These are <b>platform defaults</b>; per-tenant configuration is Phase 2 and deliberately NOT modelled here.
     * Bound as Boot durations (e.g. {@code 1h}, {@code 12h}, {@code 30d}).
     */
    @Getter
    @Setter
    public static class Session {
        /** Sliding inactivity window; reset on each successful validation. */
        private Duration idleTimeout = Duration.ofHours(1);
        /** Hard cap from creation regardless of activity. */
        private Duration absoluteTimeout = Duration.ofHours(12);
        /** Absolute cap when the remember-me flag is set at creation (idle timeout still applies). */
        private Duration rememberMeTimeout = Duration.ofDays(30);
        /** Whether remember-me is permitted at all (platform policy). */
        private boolean rememberMeAllowed = true;
        /** Redis key prefix for the session hot store ({@code {prefix}{sessionKey}}). Tenant-navigable by design. */
        private String redisKeyPrefix = "closeauth:authsession:";
        /** The session cookie carrying the opaque {@code session_key} (Stage 6a). */
        private Cookie cookie = new Cookie();

        /**
         * Session-cookie attributes (§7.5, Stage 6a). Defaults are the secure production posture; {@code secure} is
         * overridable to {@code false} for plain-HTTP local/dev and tests. {@code SameSite=Lax} is correct for an
         * Authorization Server session cookie: the OAuth flow uses top-level redirects (so the cookie is sent on the
         * return to {@code /oauth2/authorize}), while Lax still blocks it on cross-site sub-requests (CSRF mitigation).
         */
        @Getter
        @Setter
        public static class Cookie {
            private String name = "CLOSEAUTH_SESSION";
            private boolean secure = true;
            private boolean httpOnly = true;
            private String sameSite = "Lax";
            private String path = "/";
        }
    }

    @Getter
    @Setter
    public static class Keys {
        private RSAPublicKey rsaPublicKey;
        private RSAPrivateKey rsaPrivateKey;
    }

    @Getter
    @Setter
    public static class ResourceServer {
        /**
         * Hostname base for auto-generated resource-server audience identifiers. An auto-created
         * resource server's audience is a conformant URI:
         * {@code https://{tenantSlug}.{audienceHostBase}/{rsSlug}} (e.g.
         * {@code https://acme.rs.closeauth.io/todomaster-api}). Set to the real hosted domain in
         * production. Explicit (non-auto) resource servers supply their own audience and ignore this.
         */
        private String audienceHostBase = "rs.closeauth.io";
    }

    @Getter
    @Setter
    public static class Security {
        /**
         * Login-policy rate limit (read by {@code LoginPolicyService}): failed password-login attempts allowed
         * per account (tenant + email) within {@link #lockoutDurationMinutes}. Only failures count — a successful
         * login never consumes budget.
         */
        private int maxLoginAttempts = 5;
        /** The rate-limit window length in minutes — a fixed window, not a separate post-limit cooldown. */
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
        /**
         * Platform-default self-registration mode, used when a tenant has no {@code tenant_registration_config} row
         * (e.g. tenants provisioned before Stage 6b-i). New tenants get a row at provisioning. One of
         * {@code OPEN|EMAIL_VERIFIED|ADMIN_APPROVED|INVITE_ONLY}.
         */
        private String defaultMode = "EMAIL_VERIFIED";
    }

    /**
     * One-time-token primitive config (§13.4, Stage 6b-i): purpose-specific lifetimes and the rate-limit / attempt-
     * lockout pairing that protects low-entropy numeric codes. Single source for the {@code OneTimeTokenService} and
     * the flows composed over it.
     */
    @Getter
    @Setter
    public static class OneTimeToken {
        // Lifetimes (purpose-specific; short for low-entropy codes, longer for high-entropy links).
        private Duration emailVerificationTtl = Duration.ofMinutes(15);
        private int emailVerificationCodeLength = 6;
        private Duration magicLinkTtl = Duration.ofMinutes(15);
        private Duration passwordResetTtl = Duration.ofMinutes(30);
        private Duration inviteTtl = Duration.ofDays(7);
        /** Tenant-admin onboarding link; matches the temp credential's own 7-day lifetime (§2.3), not the unrelated forgot-password TTL. */
        private Duration tenantAdminOnboardingTtl = Duration.ofDays(7);

        // Issuance rate-limit (per tenant+purpose+target) — anti email-bombing / enumeration-via-issuance.
        private int issuanceMaxPerWindow = 3;
        private Duration issuanceWindow = Duration.ofMinutes(15);

        // Consume attempt-lockout (per tenant+purpose+target) — the mandatory pairing for low-entropy numeric codes.
        private int verifyMaxAttemptsPerWindow = 5;
        private Duration verifyAttemptWindow = Duration.ofMinutes(15);
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
         * consent-page is derived from this in application.properties. login-page is gone (BE-B) — the login
         * redirect is now built dynamically per tenant by TenantAwareLoginRedirectEntryPoint.
         */
        private String baseUrl = "http://localhost:8080";
        private String consentPage;
        /**
         * Redirect URI for the (future, UI-3) tenant-admin dashboard's OAuth2 authorization-code + PKCE login flow,
         * used as the auto-provisioned {@code admin-console-{tenantSlug}} public client's sole redirect URI. The
         * path itself is not implemented yet (UI-3 builds it) — this only registers the value consistently now so
         * that client's redirect_uri is never ambiguous or missing.
         */
        private String adminCallback;

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

