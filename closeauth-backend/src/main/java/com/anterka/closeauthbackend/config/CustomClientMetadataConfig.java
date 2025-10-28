package com.anterka.closeauthbackend.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.oidc.OidcClientRegistration;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcClientConfigurationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.oidc.converter.OidcClientRegistrationRegisteredClientConverter;
import org.springframework.security.oauth2.server.authorization.oidc.converter.RegisteredClientOidcClientRegistrationConverter;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.util.CollectionUtils;

public class CustomClientMetadataConfig {

    public static Consumer<List<AuthenticationProvider>> configureCustomClientMetadataConverters() {
        // Custom metadata fields that will be stored in ClientSettings
        List<String> customClientMetadata = List.of("logo_uri", "ui_config");

        // Client settings that can be controlled via OIDC registration
        List<String> clientSettingsFields = List.of("require_proof_key", "require_authorization_consent");

        return (authenticationProviders) -> {
            CustomRegisteredClientConverter registeredClientConverter =
                    new CustomRegisteredClientConverter(customClientMetadata, clientSettingsFields);
            CustomClientRegistrationConverter clientRegistrationConverter =
                    new CustomClientRegistrationConverter(customClientMetadata, clientSettingsFields);

            authenticationProviders.forEach((authenticationProvider) -> {
                if (authenticationProvider instanceof OidcClientRegistrationAuthenticationProvider provider) {
                    provider.setRegisteredClientConverter(registeredClientConverter);
                    provider.setClientRegistrationConverter(clientRegistrationConverter);
                }
                if (authenticationProvider instanceof OidcClientConfigurationAuthenticationProvider provider) {
                    provider.setClientRegistrationConverter(clientRegistrationConverter);
                }
            });
        };
    }

    private static class CustomRegisteredClientConverter
            implements Converter<OidcClientRegistration, RegisteredClient> {

        private final List<String> customClientMetadata;
        private final List<String> clientSettingsFields;
        private final OidcClientRegistrationRegisteredClientConverter delegate;

        private CustomRegisteredClientConverter(List<String> customClientMetadata, List<String> clientSettingsFields) {
            this.customClientMetadata = customClientMetadata;
            this.clientSettingsFields = clientSettingsFields;
            this.delegate = new OidcClientRegistrationRegisteredClientConverter();
        }

        @Override
        public RegisteredClient convert(OidcClientRegistration clientRegistration) {
            RegisteredClient registeredClient = this.delegate.convert(clientRegistration);
            assert registeredClient != null;
            ClientSettings.Builder clientSettingsBuilder = ClientSettings.withSettings(
                    registeredClient.getClientSettings().getSettings());

            // Handle custom metadata fields (logo_uri, ui_config, etc.)
            if (!CollectionUtils.isEmpty(this.customClientMetadata)) {
                clientRegistration.getClaims().forEach((claim, value) -> {
                    if (this.customClientMetadata.contains(claim)) {
                        clientSettingsBuilder.setting(claim, value);
                    }
                });
            }

            // Handle client settings fields with proper mapping
            if (!CollectionUtils.isEmpty(this.clientSettingsFields)) {
                clientRegistration.getClaims().forEach((claim, value) -> {
                    if (this.clientSettingsFields.contains(claim)) {
                        applyClientSetting(clientSettingsBuilder, claim, value);
                    }
                });
            }

            return RegisteredClient.from(registeredClient)
                    .clientSettings(clientSettingsBuilder.build())
                    .build();
        }

        private void applyClientSetting(ClientSettings.Builder builder, String setting, Object value) {
            switch (setting) {
                case "require_proof_key":
                    if (value instanceof Boolean) {
                        builder.requireProofKey((Boolean) value);
                    }
                    break;
                case "require_authorization_consent":
                    if (value instanceof Boolean) {
                        builder.requireAuthorizationConsent((Boolean) value);
                    }
                    break;
                // Add more settings as needed
                default:
                    // Store as custom setting
                    builder.setting(setting, value);
            }
        }
    }

    private static class CustomClientRegistrationConverter
            implements Converter<RegisteredClient, OidcClientRegistration> {

        private final List<String> customClientMetadata;
        private final List<String> clientSettingsFields;
        private final RegisteredClientOidcClientRegistrationConverter delegate;

        private CustomClientRegistrationConverter(List<String> customClientMetadata, List<String> clientSettingsFields) {
            this.customClientMetadata = customClientMetadata;
            this.clientSettingsFields = clientSettingsFields;
            this.delegate = new RegisteredClientOidcClientRegistrationConverter();
        }

        @Override
        public OidcClientRegistration convert(RegisteredClient registeredClient) {
            OidcClientRegistration clientRegistration = this.delegate.convert(registeredClient);
            Map<String, Object> claims = new HashMap<>(clientRegistration.getClaims());
            ClientSettings clientSettings = registeredClient.getClientSettings();

            // Include custom metadata fields
            if (!CollectionUtils.isEmpty(this.customClientMetadata)) {
                claims.putAll(this.customClientMetadata.stream()
                        .filter(metadata -> clientSettings.getSetting(metadata) != null)
                        .collect(Collectors.toMap(Function.identity(), clientSettings::getSetting)));
            }

            // Include client settings fields with proper extraction
            if (!CollectionUtils.isEmpty(this.clientSettingsFields)) {
                this.clientSettingsFields.forEach(setting -> {
                    Object value = extractClientSetting(clientSettings, setting);
                    if (value != null) {
                        claims.put(setting, value);
                    }
                });
            }

            return OidcClientRegistration.withClaims(claims).build();
        }

        private Object extractClientSetting(ClientSettings clientSettings, String setting) {
            return switch (setting) {
                case "require_proof_key" -> clientSettings.isRequireProofKey();
                case "require_authorization_consent" -> clientSettings.isRequireAuthorizationConsent();
                // Add more settings as needed
                default -> clientSettings.getSetting(setting);
            };
        }

    }

}
