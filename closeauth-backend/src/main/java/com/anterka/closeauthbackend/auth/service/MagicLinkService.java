package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.service.AuditEmitter;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.IssueTokenCommand;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import com.anterka.closeauthbackend.common.security.RedisRateLimiter;
import com.anterka.closeauthbackend.common.security.TenantContext;
import com.anterka.closeauthbackend.identity.dto.UserView;
import com.anterka.closeauthbackend.identity.service.UserService;
import com.anterka.closeauthbackend.notification.service.AuthNotificationSender;
import com.anterka.closeauthbackend.notification.service.NotificationDeliveryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Magic-link login (Stage 6b-i) — an alternate credential path that authenticates by proving control of the email
 * (a high-entropy opaque {@code MAGIC_LINK} token), in lieu of a password. Composes:
 * <ul>
 *   <li><b>request</b> — enumeration-safe: identical behavior whether or not the email exists (never reveals account
 *       existence); rate-limited issuance.</li>
 *   <li><b>consume</b> — the primitive's single-use consume, THEN {@code LoginPolicyService.isLoginAllowed} (a
 *       suspended tenant/user cannot magic-link in). The controller then completes the same login as 6a
 *       (createSession + cookie + resume the OAuth flow), tagging the session {@code amr=magic_link} (RFC 8176) —
 *       distinct from {@code idp} (the user is still their existing LOCAL_PASSWORD identity). This closes 6a's D3
 *       method seam.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MagicLinkService {

    /** The result of consuming a magic link: whether the user is authenticated + authorized to complete login. */
    public record MagicLinkAuthResult(boolean authenticated, UUID userId, UUID tenantId) {
        static MagicLinkAuthResult denied(UUID tenantId) {
            return new MagicLinkAuthResult(false, null, tenantId);
        }
    }

    private final OneTimeTokenService oneTimeTokenService;
    private final RedisRateLimiter rateLimiter;
    private final AuthNotificationSender notifier;
    private final UserService userService;
    private final LoginPolicyService loginPolicyService;
    private final CloseAuthProperties properties;
    private final AuditEmitter auditEmitter;

    /** Issues + delivers a magic link if the email belongs to a user; a no-op otherwise (enumeration-safe). */
    @Transactional
    public void requestMagicLink(TenantContext context, String email, String clientId) {
        String target = normalize(email);
        CloseAuthProperties.OneTimeToken cfg = properties.getOneTimeToken();
        if (!rateLimiter.tryAcquire(issueKey(context, target), cfg.getIssuanceMaxPerWindow(), cfg.getIssuanceWindow())) {
            log.info("Magic-link issuance rate-limited for a target in tenant {}", context.tenantId());
            return;
        }
        if (!userService.existsByEmail(context, target)) {
            log.info("Magic-link requested for an unknown email in tenant {} (no-op, enumeration-safe)", context.tenantId());
            return; // identical externally to the exists case
        }
        UserView user = userService.getUserByEmail(context, target);
        RawOneTimeToken raw = oneTimeTokenService.issue(new IssueTokenCommand(
                OneTimeTokenPurpose.MAGIC_LINK, context.tenantId(), user.id(), target, null,
                OneTimeTokenFormat.OPAQUE_LINK, cfg.getMagicLinkTtl()));
        try {
            notifier.sendMagicLink(target, magicLinkUrl(raw.rawSecret(), clientId));
        } catch (NotificationDeliveryException deliveryFailure) {
            // Enumeration-safety: the response must be identical whether or not the account exists AND whether or not
            // SMTP is up. Swallow + log (recipient + event only, never the link) — an outage is an ops/log concern,
            // never a per-request signal an unauthenticated caller could use to enumerate accounts.
            log.warn("[notification] MAGIC_LINK delivery failed to {} (link not logged); returning uniform response",
                    target);
        }
        auditEmitter.emit(AuditEvents.magicLinkIssued(context.tenantId(), user.id(), target));
    }

    /** Consumes a magic link and applies login policy. Returns whether the user may complete login. */
    @Transactional
    public MagicLinkAuthResult consume(String rawToken, UUID tenantId) {
        ConsumeResult result = oneTimeTokenService.consume(rawToken, OneTimeTokenPurpose.MAGIC_LINK, tenantId);
        if (!result.success()) {
            return MagicLinkAuthResult.denied(tenantId);
        }
        if (!loginPolicyService.isLoginAllowed(tenantId, result.userId())) {
            return MagicLinkAuthResult.denied(tenantId); // suspended tenant/user cannot magic-link in
        }
        auditEmitter.emit(AuditEvents.loginSuccess(tenantId, result.userId(), null, null, "magic_link"));
        return new MagicLinkAuthResult(true, result.userId(), tenantId);
    }

    private String magicLinkUrl(String rawSecret, String clientId) {
        // Backend consume endpoint (it establishes the session and resumes the OAuth flow). Real deployments include
        // the servlet context path; the exact URL is immaterial to tests (they capture the token).
        String url = properties.getIssuerUrl() + "/magic-link/consume?token=" + enc(rawSecret);
        return clientId == null ? url : url + "&client_id=" + enc(clientId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String issueKey(TenantContext context, String target) {
        return "rl:ott:issue:MAGIC_LINK:" + context.tenantId() + ":" + target;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
