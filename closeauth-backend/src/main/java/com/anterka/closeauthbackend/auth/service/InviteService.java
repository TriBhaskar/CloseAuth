package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.InviteView;
import com.anterka.closeauthbackend.auth.dto.IssueInviteCommand;
import com.anterka.closeauthbackend.auth.dto.IssueTokenCommand;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.repository.OneTimeTokenRepository;
import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.common.validation.CommandValidator;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The admin-side of INVITE_ONLY registration (§7.8) — the Stage 7b <b>issuance trigger</b> over 6b-i's INVITE one-time
 * token primitive (6b-i built consumption via {@code InviteOnlyRegistrationStrategy}). A {@code TENANT_ADMIN} issues an
 * invite; the opaque secret is emailed (via {@link AuthNotificationSender}); registration later consumes it. Thin
 * orchestration — the OTT primitive, hashing, and single-use guarantee already exist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteService {

    private final OneTimeTokenService oneTimeTokenService;
    private final OneTimeTokenRepository oneTimeTokenRepository;
    private final AuthNotificationSender notifier;
    private final CommandValidator commandValidator;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    /** Issues an invite: invalidates prior outstanding invites to the same email, mints a fresh INVITE OTT, emails it. */
    @Transactional
    public InviteView issueInvite(TenantContext context, IssueInviteCommand command) {
        commandValidator.validate(command);
        String email = normalize(command.email());
        // Single active invite per target (hygiene, mirrors password-reset).
        oneTimeTokenService.invalidateForTarget(OneTimeTokenPurpose.INVITE, context.tenantId(), email);
        RawOneTimeToken raw = oneTimeTokenService.issue(new IssueTokenCommand(
                OneTimeTokenPurpose.INVITE, context.tenantId(), null, email, null,
                OneTimeTokenFormat.OPAQUE_LINK, properties.getOneTimeToken().getInviteTtl()));
        notifier.sendInviteLink(email, inviteUrl(raw.rawSecret()));
        auditEmitter.emit(AuditEvents.inviteIssued(context.tenantId(), email, raw.tokenId()));
        log.info("Invite issued tenant={} email={} expiresAt={} (raw NOT logged)",
                context.tenantId(), email, raw.expiresAt());
        return new InviteView(raw.tokenId(), email, raw.expiresAt(), Instant.now());
    }

    /** Outstanding (unused, unexpired) invites for the tenant. */
    @Transactional(readOnly = true)
    public List<InviteView> listOutstanding(TenantContext context) {
        return oneTimeTokenRepository.findByTenantIdAndPurposeAndUsedFalseAndExpiresAtAfter(
                        context.tenantId(), OneTimeTokenPurpose.INVITE, Instant.now())
                .stream()
                .map(t -> new InviteView(t.getId(), t.getTarget(), t.getExpiresAt(), t.getCreatedAt()))
                .toList();
    }

    /** Revokes (invalidates) a specific outstanding invite. Tenant-scoped; idempotent if absent. */
    @Transactional
    public void revokeInvite(TenantContext context, UUID inviteId) {
        oneTimeTokenRepository
                .findByIdAndTenantIdAndPurpose(inviteId, context.tenantId(), OneTimeTokenPurpose.INVITE)
                .ifPresent(token -> {
                    token.setUsed(true);
                    token.setUsedAt(Instant.now());
                });
        auditEmitter.emit(AuditEvents.inviteRevoked(context.tenantId(), inviteId));
    }

    private String inviteUrl(String rawSecret) {
        return properties.getBff().getBaseUrl() + "/register?invite="
                + URLEncoder.encode(rawSecret, StandardCharsets.UTF_8);
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
