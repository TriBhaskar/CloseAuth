package com.anterka.closeauthbackend.rbac.service;

import com.anterka.closeauthbackend.common.exception.PlatformRoleNotFoundException;
import com.anterka.closeauthbackend.rbac.entity.PlatformRole;
import com.anterka.closeauthbackend.rbac.entity.UserPlatformRole;
import com.anterka.closeauthbackend.rbac.repository.PlatformRoleRepository;
import com.anterka.closeauthbackend.rbac.repository.UserPlatformRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Platform-role assignment uses a bare {@code UUID} (not {@code TenantContext}) — platform roles are not tenant-scoped. */
class PlatformRoleServiceTest {

    private PlatformRoleRepository platformRoleRepository;
    private UserPlatformRoleRepository userPlatformRoleRepository;
    private PlatformRoleService service;

    @BeforeEach
    void setUp() {
        platformRoleRepository = Mockito.mock(PlatformRoleRepository.class);
        userPlatformRoleRepository = Mockito.mock(UserPlatformRoleRepository.class);
        service = new PlatformRoleService(platformRoleRepository, userPlatformRoleRepository);
        when(userPlatformRoleRepository.save(any(UserPlatformRole.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private PlatformRole platformAdmin() {
        PlatformRole role = new PlatformRole();
        role.setId(UUID.randomUUID());
        role.setName(SystemRoleNames.PLATFORM_ADMIN);
        return role;
    }

    @Test
    void assignByBareUuidPersistsTheAssignment() {
        PlatformRole role = platformAdmin();
        UUID userId = UUID.randomUUID();
        UUID grantedBy = UUID.randomUUID();
        when(platformRoleRepository.findByName(SystemRoleNames.PLATFORM_ADMIN)).thenReturn(Optional.of(role));
        when(userPlatformRoleRepository.findByUserIdAndPlatformRoleId(userId, role.getId())).thenReturn(Optional.empty());

        service.assignPlatformRole(userId, SystemRoleNames.PLATFORM_ADMIN, grantedBy);

        ArgumentCaptor<UserPlatformRole> captor = ArgumentCaptor.forClass(UserPlatformRole.class);
        verify(userPlatformRoleRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getPlatformRoleId()).isEqualTo(role.getId());
        assertThat(captor.getValue().getGrantedByUserId()).isEqualTo(grantedBy);
    }

    @Test
    void assignIsIdempotentWhenAlreadyHeld() {
        PlatformRole role = platformAdmin();
        UUID userId = UUID.randomUUID();
        when(platformRoleRepository.findByName(SystemRoleNames.PLATFORM_ADMIN)).thenReturn(Optional.of(role));
        when(userPlatformRoleRepository.findByUserIdAndPlatformRoleId(userId, role.getId()))
                .thenReturn(Optional.of(new UserPlatformRole()));

        service.assignPlatformRole(userId, SystemRoleNames.PLATFORM_ADMIN, null);

        verify(userPlatformRoleRepository, never()).save(any());
    }

    @Test
    void assignUnknownRoleThrows() {
        when(platformRoleRepository.findByName("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignPlatformRole(UUID.randomUUID(), "NOPE", null))
                .isInstanceOf(PlatformRoleNotFoundException.class);
    }
}
