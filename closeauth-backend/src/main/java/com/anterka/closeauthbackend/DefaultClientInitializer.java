package com.anterka.closeauthbackend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class DefaultClientInitializer implements ApplicationRunner {

    private final RegisteredClientRepository registeredClientRepository;

    @Autowired
    public DefaultClientInitializer(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String defaultClientId = "admin-client";

        // Check if client already exists
        try {
            RegisteredClient byClientId = registeredClientRepository.findByClientId(defaultClientId);
            log.info("Default client already exists, skipping initialization {}", byClientId.getClientId());
        } catch (Exception e) {
            // Client doesn't exist, create it
            RegisteredClient defaultClient = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(defaultClientId)
                    .clientSecret("{noop}admin-secret")
                    .clientName("Default Admin Client")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri("http://localhost:8080/login/oauth2/code/admin-client")
                    .scope("read")
                    .scope("write")
                    .build();

            registeredClientRepository.save(defaultClient);
            log.info("Default client created successfully");
        }
    }
}
