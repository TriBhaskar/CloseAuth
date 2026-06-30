package com.anterka.closeauthbackend.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Supplies Flyway placeholder values (e.g. the read-only DB user's password) from
 * configuration/environment instead of hardcoding secrets inside migration SQL.
 *
 * <p>Resolution order for the read-only password:
 * <ol>
 *   <li>{@code closeauth.db.bff-readonly-password} (Spring property)</li>
 *   <li>{@code BFF_READONLY_DB_PASSWORD} (environment variable)</li>
 * </ol>
 * If neither is set, a development-only default is used and a warning is logged.
 * Production deployments MUST provide the value explicitly.
 */
@Component
@Slf4j
public class FlywayPlaceholderConfig implements FlywayConfigurationCustomizer {

    private static final String PLACEHOLDER_KEY = "bffReadonlyPassword";
    private static final String SPRING_PROPERTY = "closeauth.db.bff-readonly-password";
    private static final String ENV_VARIABLE = "BFF_READONLY_DB_PASSWORD";
    private static final String DEV_DEFAULT = "bff_readonly_password";

    private final Environment environment;

    public FlywayPlaceholderConfig(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(org.flywaydb.core.api.configuration.FluentConfiguration configuration) {
        String password = environment.getProperty(SPRING_PROPERTY);
        if (password == null || password.isBlank()) {
            password = environment.getProperty(ENV_VARIABLE);
        }
        if (password == null || password.isBlank()) {
            log.warn("No read-only DB password configured ({} / {}); using insecure development default. " +
                    "Set this explicitly in production.", SPRING_PROPERTY, ENV_VARIABLE);
            password = DEV_DEFAULT;
        }
        configuration.placeholders(Map.of(PLACEHOLDER_KEY, password));
    }
}

