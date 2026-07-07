package com.anterka.closeauthbackend.platform.service;

import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.platform.dto.CreatePlatformAdminCommand;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminView;
import com.anterka.closeauthbackend.platform.enums.PlatformAdminStatus;
import com.anterka.closeauthbackend.platform.repository.PlatformAdminRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The startup bootstrap must be <b>idempotent</b> (never a second admin) and must <b>never hardcode</b> a credential —
 * a blank configured credential skips creation entirely. It also must survive a concurrent multi-instance boot (the
 * globally-unique email losing the race is harmless).
 */
@ExtendWith(MockitoExtension.class)
class PlatformAdminBootstrapTest {

    @Mock private PlatformAdminRepository platformAdminRepository;
    @Mock private PlatformAdminService platformAdminService;

    @Test
    void skipsWhenAnyAdminAlreadyExists() {
        when(platformAdminRepository.count()).thenReturn(1L);

        bootstrap(configured("boss@x.io", "s3cretpw")).run(null);

        verifyNoInteractions(platformAdminService); // idempotent: no create attempt
    }

    @Test
    void skipsWhenNoCredentialConfigured() {
        when(platformAdminRepository.count()).thenReturn(0L);

        bootstrap(configured("", "")).run(null); // blank credential = no hardcoded fallback

        verify(platformAdminService, never()).createPlatformAdmin(any());
    }

    @Test
    void createsFirstAdminAndAssignsPlatformRoleWhenConfiguredAndEmpty() {
        when(platformAdminRepository.count()).thenReturn(0L);
        UUID id = UUID.randomUUID();
        when(platformAdminService.createPlatformAdmin(any()))
                .thenReturn(new PlatformAdminView(id, "boss@x.io", PlatformAdminStatus.ACTIVE, "Platform", "Admin",
                        null, Instant.now()));

        bootstrap(configured("Boss@x.io", "s3cretpw")).run(null);

        verify(platformAdminService).createPlatformAdmin(any(CreatePlatformAdminCommand.class));
        verify(platformAdminService).assignRole(eq(id), eq("PLATFORM_ADMIN"));
    }

    @Test
    void swallowsConcurrentBootRace() {
        when(platformAdminRepository.count()).thenReturn(0L);
        doThrow(new RuntimeException("duplicate key value violates unique constraint"))
                .when(platformAdminService).createPlatformAdmin(any());

        // The losing node must NOT crash startup.
        assertThatCode(() -> bootstrap(configured("boss@x.io", "s3cretpw")).run(null)).doesNotThrowAnyException();
    }

    private PlatformAdminBootstrap bootstrap(CloseAuthProperties properties) {
        return new PlatformAdminBootstrap(platformAdminRepository, platformAdminService, properties);
    }

    private CloseAuthProperties configured(String email, String password) {
        CloseAuthProperties properties = new CloseAuthProperties();
        properties.getPlatformAdmin().setBootstrapEmail(email);
        properties.getPlatformAdmin().setBootstrapPassword(password);
        return properties;
    }
}
