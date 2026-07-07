package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.web.PageView;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
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
 * Tenant-scoped user administration (§7.8). {@link RequiresTenantAccess} enforces platform-admin-OR-{@code TENANT_ADMIN}-
 * of-this-tenant (the 7a cross-tenant guard). Thin surface over {@link UserService} (3b); tenant-scoping is enforced by
 * the gate AND by the service ({@code TenantContext}) — defense in depth.
 *
 * <p><b>Suspend/delete are more than status flips:</b> {@code UserService} refuses removing the last {@code TENANT_ADMIN}
 * (409) and writes the token-revocation marker (4b-ii) so the user's live tokens die. See STAGE_7B_REPORT.md.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /users?page&size} · {@code GET /users/{userId}} · {@code POST /users} (create, 201) ·
 * {@code POST /users/{userId}/suspend|activate|approve} · {@code DELETE /users/{userId}} (soft-delete).
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}")
public class TenantUserController {

    private final UserService userService;

    @GetMapping("/users")
    public PageView<UserView> list(@PathVariable String tenantId,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return PageView.of(userService.listUsers(ctx(tenantId)), page, size);
    }

    @GetMapping("/users/{userId}")
    public UserView get(@PathVariable String tenantId, @PathVariable UUID userId) {
        return userService.getUserById(ctx(tenantId), userId);
    }

    @PostMapping("/users")
    public ResponseEntity<UserView> create(@PathVariable String tenantId,
                                           @Valid @RequestBody CreateUserWithPasswordCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUserWithPassword(ctx(tenantId), command));
    }

    @PostMapping("/users/{userId}/suspend")
    public UserView suspend(@PathVariable String tenantId, @PathVariable UUID userId) {
        return userService.suspendUser(ctx(tenantId), userId); // last-admin guard + token revocation live in the service
    }

    @PostMapping("/users/{userId}/activate")
    public UserView activate(@PathVariable String tenantId, @PathVariable UUID userId) {
        return userService.activateUser(ctx(tenantId), userId);
    }

    @DeleteMapping("/users/{userId}")
    public UserView delete(@PathVariable String tenantId, @PathVariable UUID userId) {
        return userService.deleteUser(ctx(tenantId), userId); // soft-delete; same guard + revocation
    }

    /**
     * Approves a PENDING user (ADMIN_APPROVED registration trigger, §7.8) — the admin-side of 6b-i. Refuses (409) if the
     * user is not PENDING (only a pending user can be approved).
     */
    @PostMapping("/users/{userId}/approve")
    public UserView approve(@PathVariable String tenantId, @PathVariable UUID userId) {
        TenantContext context = ctx(tenantId);
        UserView user = userService.getUserById(context, userId);
        if (user.status() != UserStatus.PENDING) {
            throw new CloseAuthDomainException(ErrorCategory.STATE, "user.not_pending",
                    "Only a PENDING user can be approved");
        }
        return userService.activateUser(context, userId);
    }

    private TenantContext ctx(String tenantId) {
        return TenantContext.of(UUID.fromString(tenantId));
    }
}
