package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.auth.dto.CreateUserWithTempCredentialCommand;
import com.anterka.closeauthbackend.auth.dto.TenantAdminBootstrappedView;
import com.anterka.closeauthbackend.auth.service.TenantOnboardingService;
import com.anterka.closeauthbackend.common.exception.CloseAuthDomainException;
import com.anterka.closeauthbackend.common.exception.ErrorCategory;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.web.PageView;
import com.anterka.closeauthbackend.identity.dto.CreateUserWithPasswordCommand;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.enums.UserStatus;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.rbac.service.TenantRoleService;
import com.anterka.closeauthbackend.session.dto.MeSessionView;
import com.anterka.closeauthbackend.session.dto.SessionView;
import com.anterka.closeauthbackend.session.service.AuthServerSessionService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped user administration (§7.8). {@link RequiresTenantAccess} enforces platform-admin-OR-{@code TENANT_ADMIN}-
 * of-this-tenant (the 7a cross-tenant guard). Thin surface over {@link UserService} (3b); tenant-scoping is enforced by
 * the gate AND by the service ({@code TenantContext}) — defense in depth.
 *
 * <p><b>Suspend/delete are more than status flips:</b> {@code UserService} refuses removing the last {@code TENANT_ADMIN}
 * (409), writes the token-revocation marker (4b-ii), and (FE-4a) revokes the user's Auth Server sessions too — so
 * their live tokens die AND their existing browser session stops working immediately. See STAGE_7B_REPORT.md.
 *
 * <p><b>FE-4a additions:</b> {@code list}/{@code get} decorate {@link UserView#roles()}/{@link UserView#isLastActiveAdmin()}
 * via {@link TenantRoleService} — merged here at the controller layer, the same "decorate the DTO at the boundary"
 * convention {@code PlatformTenantController} already uses for {@code TenantView#adminCount}. Session endpoints mirror
 * {@code MeController}'s self-service ones, but admin-scoped: any session belonging to a user OF THIS TENANT, not just
 * the caller's own.
 *
 * <h2>HTTP contract</h2>
 * {@code GET /users?page&size&status&role&q} · {@code GET /users/{userId}} · {@code POST /users} (create, 201) ·
 * {@code POST /users/{userId}/suspend|activate|approve} · {@code DELETE /users/{userId}} (soft-delete) ·
 * {@code GET /users/{userId}/sessions} · {@code DELETE /users/{userId}/sessions/{sessionId}} ·
 * {@code DELETE /users/{userId}/sessions} (revoke all).
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}")
public class TenantUserController {

    private final UserService userService;
    private final TenantRoleService tenantRoleService;
    private final AuthServerSessionService sessionService;
    private final TenantOnboardingService tenantOnboardingService;

    @GetMapping("/users")
    public PageView<UserView> list(@PathVariable String tenantId,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   @RequestParam(required = false) UserStatus status,
                                   @RequestParam(required = false) String role,
                                   @RequestParam(required = false) String q) {
        TenantContext context = ctx(tenantId);
        Map<UUID, List<String>> rolesByUser = tenantRoleService.getTenantRoleNamesByUser(context);
        List<UserView> users = userService.listUsers(context, status, role, q).stream()
                .map(u -> u.withRoles(rolesByUser.getOrDefault(u.id(), List.of())))
                .toList();
        return PageView.of(users, page, size);
    }

    @GetMapping("/users/{userId}")
    public UserView get(@PathVariable String tenantId, @PathVariable UUID userId) {
        TenantContext context = ctx(tenantId);
        UserView user = userService.getUserById(context, userId);
        boolean isLastActiveAdmin = tenantRoleService.isLastTenantAdmin(context, userId);
        return user.withRoles(tenantRoleService.getTenantRolesForUser(context, userId))
                .withIsLastActiveAdmin(isLastActiveAdmin);
    }

    @PostMapping("/users")
    public ResponseEntity<UserView> create(@PathVariable String tenantId,
                                           @Valid @RequestBody CreateUserWithPasswordCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUserWithPassword(ctx(tenantId), command));
    }

    /**
     * FE-4a: the temporary-password create mode (spec §6.4.2) — {@link TenantOnboardingService} generates the
     * password, sets {@code must_change_password=true} + a 7-day expiry, and returns the password exactly once.
     * Distinct from {@code POST /users}, which takes an admin-typed password with no forced rotation.
     */
    @PostMapping("/users/with-temp-credential")
    public ResponseEntity<TenantAdminBootstrappedView> createWithTempCredential(
            @PathVariable String tenantId, @Valid @RequestBody CreateUserWithTempCredentialCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tenantOnboardingService.createUserWithTempCredential(ctx(tenantId), command));
    }

    @PostMapping("/users/{userId}/suspend")
    public UserView suspend(@PathVariable String tenantId, @PathVariable UUID userId) {
        return userService.suspendUser(ctx(tenantId), userId); // last-admin guard + token/session revocation live in the service
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

    /**
     * FE-4a: admin-scoped session list for one tenant user — same shape as {@code MeController}'s self-service
     * {@code GET /v1/me/sessions} ({@code MeSessionView} deliberately omits {@code session_key}), just reachable by
     * a tenant admin for a user of THIS tenant rather than only the caller's own.
     */
    @GetMapping("/users/{userId}/sessions")
    public List<MeSessionView> sessions(@PathVariable String tenantId, @PathVariable UUID userId) {
        return sessionService.listSessionsForUser(ctx(tenantId).tenantId(), userId).stream()
                .map(MeSessionView::from)
                .toList();
    }

    @DeleteMapping("/users/{userId}/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(@PathVariable String tenantId, @PathVariable UUID userId,
                                              @PathVariable UUID sessionId) {
        UUID tid = ctx(tenantId).tenantId();
        SessionView session = sessionService.getById(sessionId)
                .filter(s -> userId.equals(s.userId()) && tid.equals(s.tenantId()))
                .orElseThrow(() -> new CloseAuthDomainException(
                        ErrorCategory.NOT_FOUND, "session.not_found", "Session not found"));
        sessionService.revokeSession(session.sessionKey());
        return ResponseEntity.noContent().build();
    }

    /** Revokes every session belonging to this user (spec §6.4.2's "Revoke all sessions", typed-confirm gated in the UI). */
    @DeleteMapping("/users/{userId}/sessions")
    public ResponseEntity<Void> revokeAllSessions(@PathVariable String tenantId, @PathVariable UUID userId) {
        sessionService.revokeAllUserSessions(ctx(tenantId).tenantId(), userId);
        return ResponseEntity.noContent().build();
    }

    private TenantContext ctx(String tenantId) {
        return TenantContext.of(UUID.fromString(tenantId));
    }
}
