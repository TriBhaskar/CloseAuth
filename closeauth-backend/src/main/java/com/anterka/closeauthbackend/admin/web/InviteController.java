package com.anterka.closeauthbackend.admin.web;

import com.anterka.closeauthbackend.admin.security.RequiresTenantAccess;
import com.anterka.closeauthbackend.auth.dto.InviteView;
import com.anterka.closeauthbackend.auth.dto.IssueInviteCommand;
import com.anterka.closeauthbackend.auth.service.InviteService;
import com.anterka.closeauthbackend.common.security.TenantContext;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Invite issuance (§7.8, INVITE_ONLY registration trigger) — the admin-side of 6b-i's INVITE one-time token.
 * {@link RequiresTenantAccess} gate. Wraps {@link InviteService}. The raw invite secret is delivered only by email
 * (never in a response).
 *
 * <h2>HTTP contract</h2>
 * {@code POST /v1/tenants/{tenantId}/invites} (issue, 201) · {@code GET .../invites} (outstanding) ·
 * {@code DELETE .../invites/{inviteId}} (revoke).
 */
@RestController
@RequiredArgsConstructor
@RequiresTenantAccess
@RequestMapping("/v1/tenants/{tenantId}/invites")
public class InviteController {

    private final InviteService inviteService;

    @PostMapping
    public ResponseEntity<InviteView> issue(@PathVariable String tenantId,
                                            @Valid @RequestBody IssueInviteCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inviteService.issueInvite(ctx(tenantId), command));
    }

    @GetMapping
    public List<InviteView> list(@PathVariable String tenantId) {
        return inviteService.listOutstanding(ctx(tenantId));
    }

    @DeleteMapping("/{inviteId}")
    public ResponseEntity<Void> revoke(@PathVariable String tenantId, @PathVariable UUID inviteId) {
        inviteService.revokeInvite(ctx(tenantId), inviteId);
        return ResponseEntity.noContent().build();
    }

    private TenantContext ctx(String tenantId) {
        return TenantContext.of(UUID.fromString(tenantId));
    }
}
