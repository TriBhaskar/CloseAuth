package com.anterka.closeauthbackend.common.config;

import com.anterka.closeauthbackend.common.config.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPooled;

/**
 * Redis configuration using type-safe configuration properties.
 */
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
@RequiredArgsConstructor
@Slf4j
public class RedisConfig {

    private final RedisProperties redisProperties;

    @Bean
    public JedisPooled jedisPooled() {
        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMinIdle(redisProperties.getPool().getMinIdle());
        poolConfig.setMaxIdle(redisProperties.getPool().getMaxIdle());
        poolConfig.setMaxTotal(redisProperties.getPool().getMaxActive());

        JedisPooled jedisClient = new JedisPooled(
                poolConfig,
                redisProperties.getHost(),
                redisProperties.getPort(),
                redisProperties.getTimeout(),
                redisProperties.getPassword(),
                redisProperties.isUseSsl()
        );

        try {
            if (jedisClient.ping().equalsIgnoreCase("PONG")) {
                log.info("Successfully connected to Redis at {}:{}",
                        redisProperties.getHost(), redisProperties.getPort());
            }
        } catch (Exception e) {
            log.error("Failed to connect to Redis: {}", e.getMessage());
        }

        return jedisClient;
    }

    // Convenience methods for rate limiting (for backward compatibility)
    public int getForgotPasswordRateLimit() {
        return redisProperties.getRateLimit().getForgotPassword();
    }

    public int getValidateTokenRateLimit() {
        return redisProperties.getRateLimit().getValidateToken();
    }

    public int getResetPasswordRateLimit() {
        return redisProperties.getRateLimit().getResetPassword();
    }

    public int getWindowMinutesRateLimit() {
        return redisProperties.getRateLimit().getWindowMinutes();
    }
}
