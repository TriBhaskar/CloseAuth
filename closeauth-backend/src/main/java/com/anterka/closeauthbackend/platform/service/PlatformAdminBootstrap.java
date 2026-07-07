package com.anterka.closeauthbackend.platform.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.platform.dto.CreatePlatformAdminCommand;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminView;
import com.anterka.closeauthbackend.platform.repository.PlatformAdminRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the FIRST platform admin at startup (§7.8, Stage 7a) — the chicken-and-egg resolution (you can't require an
 * existing platform admin to create the first). <b>Idempotent</b>: if any platform admin already exists, it is a
 * no-op; only an empty {@code platform_admins} table triggers creation, and only when a bootstrap credential is
 * configured. <b>No credential is hardcoded</b> — email/password come from {@code closeauth.platform-admin.bootstrap-*}
 * (env/secret manager); a blank credential skips bootstrap.
 *
 * <p>This is the clean spiritual replacement for the old {@code DefaultClientInitializer} (deleted Stage 0) — for the
 * platform admin, not client-seeding. Runs as an {@link ApplicationRunner} (after Flyway + full context init).
 *
 * <p><b>Security:</b> the bootstrap credential is a break-glass startup secret — it MUST be rotated immediately after
 * first login and {@code bootstrap-password} unset. A loud WARN is logged on creation to that effect.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlatformAdminBootstrap implements ApplicationRunner {

    private final PlatformAdminRepository platformAdminRepository;
    private final PlatformAdminService platformAdminService;
    private final CloseAuthProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (platformAdminRepository.count() > 0) {
            log.debug("platform_admins is not empty; bootstrap skipped (idempotent).");
            return;
        }
        CloseAuthProperties.PlatformAdmin cfg = properties.getPlatformAdmin();
        if (isBlank(cfg.getBootstrapEmail()) || isBlank(cfg.getBootstrapPassword())) {
            log.warn("No platform admins exist and no bootstrap credential is configured "
                    + "(closeauth.platform-admin.bootstrap-email / bootstrap-password). The admin API is unusable "
                    + "until a platform admin exists — set the bootstrap credential (env) and restart.");
            return;
        }
        try {
            PlatformAdminView admin = platformAdminService.createPlatformAdmin(new CreatePlatformAdminCommand(
                    cfg.getBootstrapEmail(), cfg.getBootstrapPassword(),
                    cfg.getBootstrapFirstName(), cfg.getBootstrapLastName()));
            platformAdminService.assignRole(admin.id(), "PLATFORM_ADMIN");
            log.warn("BOOTSTRAP: created the initial platform admin {} (PLATFORM_ADMIN) from the configured bootstrap "
                    + "credential. ROTATE THIS CREDENTIAL IMMEDIATELY after first login and unset "
                    + "closeauth.platform-admin.bootstrap-password.", admin.email());
        } catch (RuntimeException alreadyBootstrapped) {
            // Concurrent multi-instance boot: the globally-unique email loses the race — harmless, another node won.
            log.info("Platform-admin bootstrap did not create a new admin (likely created concurrently): {}",
                    alreadyBootstrapped.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
