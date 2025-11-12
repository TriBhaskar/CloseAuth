package com.anterka.closeauthbackend.config;

import lombok.Getter;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPooled;

@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);
    @Value("${redis.host}")
    private String host;
    @Value("${redis.port}")
    private int port;
    @Value("${redis.password}")
    private String password;
    @Value("${redis.timeout}")
    private int timeout;
    @Value("${redis.max-active}")
    private int maxTotal;
    @Value("${redis.max-idle}")
    private int maxIdle;
    @Value("${redis.min-idle}")
    private int minIdle;
    @Value("${redis.use-ssl}")
    private boolean useSsl;

    /**
     * properties related to the password operations
     * */
    @Getter
    @Value("${redis.app.rate-limit.forgot-password}")
    private String forgotPasswordRateLimit;

    @Getter
    @Value("${redis.app.rate-limit.validate-token}")
    private String validateTokenRateLimit;

    @Getter
    @Value("${redis.app.rate-limit.reset-password}")
    private String resetPasswordRateLimit;

    @Getter
    @Value("${redis.app.rate-limit.window-minutes}")
    private String windowMinutesRateLimit;

    @Bean
    public JedisPooled jedisPooled(){
        GenericObjectPoolConfig<Connection> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMinIdle(minIdle);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMaxTotal(maxTotal);
        JedisPooled jedisClient = new JedisPooled(poolConfig, host, port, timeout, password, useSsl);
        try{
            if(jedisClient.ping().equalsIgnoreCase("PONG")){
                log.info("Successfully connected to Redis");
            }
        }catch (Exception e){
            log.error("Failed to connect to Redis: {}", e.getMessage());
        }
        return jedisClient;
    }

}
