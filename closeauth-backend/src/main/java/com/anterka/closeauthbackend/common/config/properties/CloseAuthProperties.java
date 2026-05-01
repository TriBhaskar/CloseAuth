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
        private String loginPage;
        private String consentPage;
    }
}

