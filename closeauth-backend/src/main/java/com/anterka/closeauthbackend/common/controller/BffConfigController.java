package com.anterka.closeauthbackend.common.controller;

import com.anterka.closeauthbackend.common.constants.ApiPaths;
import com.anterka.closeauthbackend.common.dto.BffConfigResponse;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Public configuration endpoint for the BFF (Backend-for-Frontend).
 *
 * The BFF calls this at startup (before it has an OAuth2 token) to
 * fetch configuration values that must stay in sync between the two
 * services: session timeouts, OTP settings, endpoint paths, etc.
 *
 * This eliminates hardcoded values in the BFF and prevents subtle
 * bugs caused by configuration drift between deployments.
 *
 * Security: this endpoint is permitAll() in Filter Chain 4 — it
 * exposes only non-sensitive operational configuration, no secrets.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BffConfigController {

    private static final String SERVER_VERSION = "1.0.0";
    private static final String MIN_BFF_VERSION = "1.0.0";
    private static final String API_VERSION = "v1";

    private final CloseAuthProperties properties;

    /**
     * Returns the full BFF configuration payload.
     *
     * Response is cacheable for 5 minutes to prevent
     * excessive polling during health-check loops or retries.
     */
    @GetMapping(ApiPaths.BFF_CONFIG_ENDPOINT)
    public ResponseEntity<BffConfigResponse> getBffConfig() {
        log.debug("BFF config requested");

        BffConfigResponse response = BffConfigResponse.builder()
                .version(BffConfigResponse.VersionInfo.builder()
                        .api(API_VERSION)
                        .server(SERVER_VERSION)
                        .minBffVersion(MIN_BFF_VERSION)
                        .build())
                .session(BffConfigResponse.SessionConfig.builder()
                        .timeoutSeconds(properties.getBff().getSessionTimeoutSeconds())
                        .oauthContextTtlSeconds(properties.getBff().getOauthContextTtlSeconds())
                        .build())
                .security(BffConfigResponse.SecurityConfig.builder()
                        .maxLoginAttempts(properties.getSecurity().getMaxLoginAttempts())
                        .lockoutDurationMinutes(properties.getSecurity().getLockoutDurationMinutes())
                        .build())
                .otp(BffConfigResponse.OtpConfig.builder()
                        .length(properties.getOtp().getLength())
                        .validitySeconds(properties.getOtp().getValiditySeconds())
                        .resendRateLimit(properties.getOtp().getResendRateLimit())
                        .build())
                .endpoints(BffConfigResponse.EndpointsConfig.builder()
                        .loginProcessingUrl("/login")
                        .consentSubmitUrl(ApiPaths.AUTHORIZE_URL)
                        .contextPath(ApiPaths.API_CONTEXT_PATH)
                        .apiPrefix(ApiPaths.API_V1_BASE)
                        .clientInfoUrl(ApiPaths.CLIENT_INFO_URL)
                        .registerUserUrl(ApiPaths.USER_REGISTER_URL)
                        .adminAuthBase(ApiPaths.ADMIN_BASE + ApiPaths.AUTH_BASE)
                        .clientConfigBase(ApiPaths.CLIENT_CONFIG_BASE)
                        .build())
                .registration(BffConfigResponse.RegistrationConfig.builder()
                        .cacheTtlHours(properties.getRegistration().getCacheTtlHours())
                        .adminPendingTtlDays(properties.getRegistration().getAdminPendingTtlDays())
                        .build())
                .features(BffConfigResponse.FeaturesConfig.builder()
                        .dynamicClientRegistration(true)
                        .build())
                .build();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(response);
    }
}

