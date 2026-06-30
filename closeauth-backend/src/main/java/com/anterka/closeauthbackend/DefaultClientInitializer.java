package com.anterka.closeauthbackend;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultClientInitializer implements ApplicationRunner {

    private static final long ONE_YEAR_SECONDS = 31_536_000L;

    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;
    private final CloseAuthProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        CloseAuthProperties.Bootstrap bootstrap = properties.getBootstrap();

        if (!bootstrap.isEnabled()) {
            log.info("Bootstrap client seeding is disabled; skipping.");
            return;
        }

        // Never create a client with an empty/guessable secret. Operators must
        // supply the secret externally (env/secret manager).
        if (bootstrap.getClientSecret() == null || bootstrap.getClientSecret().isBlank()) {
            log.warn("Bootstrap client secret is not configured (closeauth.bootstrap.client-secret); " +
                    "skipping default client creation.");
            return;
        }

        String clientId = bootstrap.getClientId();
        RegisteredClient existing = registeredClientRepository.findByClientId(clientId);
        if (existing != null) {
            log.info("Default client '{}' already exists; skipping initialization.", clientId);
            return;
        }

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(bootstrap.getClientSecret()))
                .clientSecretExpiresAt(Instant.now().plusSeconds(ONE_YEAR_SECONDS))
                .clientName("Default Admin Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(bootstrap.getRedirectUri());

        bootstrap.getScopes().forEach(builder::scope);

        registeredClientRepository.save(builder.build());
        log.info("Default client '{}' created successfully.", clientId);
    }
}

