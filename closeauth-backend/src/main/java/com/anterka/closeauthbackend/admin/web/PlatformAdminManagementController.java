package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresPlatformAdmin;
import com.anterka.closeauthbackend.common.web.PageView;
import com.anterka.closeauthbackend.platform.dto.CreatePlatformAdminCommand;
import com.anterka.closeauthbackend.platform.dto.PlatformAdminView;
import com.anterka.closeauthbackend.platform.service.PlatformAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Platform-admin management (§7.8) — creating/suspending platform admins and managing their platform roles is itself a
 * {@link RequiresPlatformAdmin} operation. Wraps {@link PlatformAdminService} (7a). Note: {@code suspend} writes the
 * platform-admin revocation marker (7a wiring), so a suspended admin's live token dies within the TTL.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /v1/platform/admins?page&size} · {@code POST /v1/platform/admins} (create, 201) ·
 * {@code POST .../{id}/suspend} · {@code POST .../{id}/activate} · {@code POST .../{id}/roles/{roleName}} (assign) ·
 * {@code DELETE .../{id}/roles/{roleName}} (revoke). Views never carry credentials.
 */
@RestController
@RequiredArgsConstructor
@RequiresPlatformAdmin
@RequestMapping("/v1/platform/admins")
public class PlatformAdminManagementController {

    private final PlatformAdminService platformAdminService;

    @GetMapping
    public PageView<PlatformAdminView> list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return PageView.of(platformAdminService.list(), page, size);
    }

    @PostMapping
    public ResponseEntity<PlatformAdminView> create(@Valid @RequestBody CreatePlatformAdminCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(platformAdminService.createPlatformAdmin(command));
    }

    @PostMapping("/{id}/suspend")
    public PlatformAdminView suspend(@PathVariable UUID id) {
        return platformAdminService.suspend(id); // also writes the revocation marker (7a)
    }

    @PostMapping("/{id}/activate")
    public PlatformAdminView activate(@PathVariable UUID id) {
        return platformAdminService.activate(id);
    }

    @PostMapping("/{id}/roles/{roleName}")
    public ResponseEntity<Void> assignRole(@PathVariable UUID id, @PathVariable String roleName) {
        platformAdminService.assignRole(id, roleName);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/roles/{roleName}")
    public ResponseEntity<Void> revokeRole(@PathVariable UUID id, @PathVariable String roleName) {
        platformAdminService.revokeRole(id, roleName);
        return ResponseEntity.noContent().build();
    }
}
