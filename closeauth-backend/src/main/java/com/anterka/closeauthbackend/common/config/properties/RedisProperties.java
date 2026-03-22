package com.anterka.closeauthbackend.common.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Redis connection and rate limiting.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "closeauth.redis")
public class RedisProperties {

    /**
     * Redis server host
     */
    private String host = "localhost";

    /**
     * Redis server port
     */
    private int port = 6379;

    /**
     * Redis password
     */
    private String password;

    /**
     * Connection timeout in milliseconds
     */
    private int timeout = 2000;

    /**
     * Whether to use SSL
     */
    private boolean useSsl = false;

    /**
     * Connection pool configuration
     */
    private Pool pool = new Pool();

    /**
     * Rate limiting configuration
     */
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class Pool {
        /**
         * Maximum number of connections in the pool
         */
        private int maxActive = 8;

        /**
         * Maximum number of idle connections in the pool
         */
        private int maxIdle = 8;

        /**
         * Minimum number of idle connections in the pool
         */
        private int minIdle = 0;
    }

    @Data
    public static class RateLimit {
        /**
         * Maximum forgot password attempts per window
         */
        private int forgotPassword = 3;

        /**
         * Maximum token validation attempts per window
         */
        private int validateToken = 5;

        /**
         * Maximum password reset attempts per window
         */
        private int resetPassword = 3;

        /**
         * Rate limit window in minutes
         */
        private int windowMinutes = 15;
    }
}

