package com.anterka.closeauthbackend.auth.service;

import com.anterka.closeauthbackend.auth.dto.ConsumeResult;
import com.anterka.closeauthbackend.auth.dto.ConsumeResult.FailureReason;
import com.anterka.closeauthbackend.auth.dto.IssueTokenCommand;
import com.anterka.closeauthbackend.auth.dto.RawOneTimeToken;
import com.anterka.closeauthbackend.auth.entity.OneTimeToken;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenFormat;
import com.anterka.closeauthbackend.auth.enums.OneTimeTokenPurpose;
import com.anterka.closeauthbackend.auth.repository.OneTimeTokenRepository;
import com.anterka.closeauthbackend.common.config.properties.CloseAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * The one-time-token primitive (§13.4) — the security centerpiece of Stage 6b-i, built ONCE and composed by all four
 * identity flows (registration email-verification, magic-link, password-reset, invite). Deliberately <b>policy-free</b>
 * (the primitive/flow discipline used throughout): rate-limiting, enumeration-safe messaging, and post-action effects
 * are the flows' concern; this owns only the secret's lifecycle.
 *
 * <p>Security properties (each enforced here, tested exhaustively):
 * <ul>
 *   <li><b>Hash storage, never plaintext</b> — only a SHA-256 hash is persisted; the raw value is returned once for
 *       out-of-band delivery and never stored or logged (fixing the old plaintext-OTP behavior).</li>
 *   <li><b>Single-use, atomically</b> — consume ends with a conditional {@code markUsedIfUnused}; exactly one
 *       concurrent consumer wins ({@code affected==1}), a replay/loser gets {@code affected==0} (same race discipline
 *       as 4b-i's refresh-token replay detection).</li>
 *   <li><b>Expiry</b> — checked on consume (and reclaimed by a sweep — see the repository).</li>
 *   <li><b>Tenant-bound</b> and <b>purpose-bound</b> — verified on consume; and for low-entropy numeric codes also
 *       <em>baked into the stored hash</em> (see below), so a code is only ever meaningful for its own
 *       tenant+purpose+recipient.</li>
 *   <li><b>Enumeration-safe consume</b> — {@link ConsumeResult} is uniform on failure; the specific reason is logged
 *       for audit only and never surfaced by the flows.</li>
 * </ul>
 *
 * <p><b>Two consume entry points, one validation core</b> — because the two formats have different lookup keys:
 * <ul>
 *   <li>{@link OneTimeTokenFormat#OPAQUE_LINK} tokens are 256-bit and globally unique, so the stored hash is
 *       {@code SHA-256(rawToken)} and {@link #consume} looks up by the raw token alone.</li>
 *   <li>{@link OneTimeTokenFormat#NUMERIC_CODE} tokens (6 digits) are NOT globally unique — hashing the bare code
 *       would collide across recipients and break the {@code token_hash} UNIQUE constraint. So a numeric code's stored
 *       hash is {@code SHA-256(tenant|purpose|target|code)}, and {@link #consumeCode} recomputes it from the same
 *       scope. This makes each code unique per recipient AND intrinsically tenant/purpose/target-bound.</li>
 * </ul>
 */
@Service
@Slf4j
public class OneTimeTokenService {

    private final OneTimeTokenRepository repository;
    private final OneTimeTokenGenerator generator;
    private final CloseAuthProperties properties;
    private final Clock clock;

    public OneTimeTokenService(OneTimeTokenRepository repository, OneTimeTokenGenerator generator,
                               CloseAuthProperties properties, Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Generates a cryptographically-random secret, stores only its (scoped) hash + metadata, and returns the raw
     * secret for out-of-band delivery. The caller (a flow) is responsible for rate-limiting issuance and delivery.
     */
    @Transactional
    public RawOneTimeToken issue(IssueTokenCommand command) {
        int codeLength = properties.getOneTimeToken().getEmailVerificationCodeLength();
        String rawSecret = generator.generate(command.format(), codeLength);
        Instant now = Instant.now(clock);

        OneTimeToken token = new OneTimeToken();
        token.setTokenHash(hashFor(command.format(), command.tenantId(), command.purpose(), command.target(), rawSecret));
        token.setPurpose(command.purpose());
        token.setTenantId(command.tenantId());
        token.setUserId(command.userId());
        token.setTarget(command.target());
        token.setPayload(command.payload());
        token.setExpiresAt(now.plus(command.ttl()));
        token.setUsed(false);
        token.setCreatedAt(now);
        OneTimeToken saved = repository.save(token);

        log.info("Issued one-time token purpose={} tenant={} target={} expiresAt={} (raw NOT logged)",
                command.purpose(), command.tenantId(), command.target(), token.getExpiresAt());
        return new RawOneTimeToken(rawSecret, saved.getId(), saved.getExpiresAt());
    }

    /** Consumes an OPAQUE_LINK token (magic-link, password-reset, invite). Enumeration-safe (uniform failure). */
    @Transactional
    public ConsumeResult consume(String rawToken, OneTimeTokenPurpose expectedPurpose, UUID expectedTenantId) {
        if (rawToken == null || rawToken.isBlank()) {
            return reject(FailureReason.NOT_FOUND);
        }
        return finishConsume(generator.hash(rawToken), expectedPurpose, expectedTenantId);
    }

    /** Consumes a NUMERIC_CODE token (email/OTP verification). The recipient scope is part of the lookup key. */
    @Transactional
    public ConsumeResult consumeCode(String code, OneTimeTokenPurpose expectedPurpose, UUID expectedTenantId,
                                     String target) {
        if (code == null || code.isBlank()) {
            return reject(FailureReason.NOT_FOUND);
        }
        return finishConsume(numericHash(expectedTenantId, expectedPurpose, target, code), expectedPurpose, expectedTenantId);
    }

    /** Invalidates outstanding (unused) tokens of a purpose for a target — e.g. before issuing a fresh reset token. */
    @Transactional
    public int invalidateForTarget(OneTimeTokenPurpose purpose, UUID tenantId, String target) {
        return repository.invalidateForTarget(purpose, tenantId, target, Instant.now(clock));
    }

    // ---- validation core ---------------------------------------------------

    private ConsumeResult finishConsume(String tokenHash, OneTimeTokenPurpose expectedPurpose, UUID expectedTenantId) {
        OneTimeToken token = repository.findByTokenHash(tokenHash).orElse(null);
        if (token == null) {
            return reject(FailureReason.NOT_FOUND);
        }
        // Defense-in-depth: purpose/tenant are already baked into a numeric hash and checked here for opaque tokens.
        if (token.getPurpose() != expectedPurpose) {
            return reject(FailureReason.WRONG_PURPOSE);
        }
        if (!token.getTenantId().equals(expectedTenantId)) {
            return reject(FailureReason.WRONG_TENANT);
        }
        if (token.getExpiresAt().isBefore(Instant.now(clock))) {
            return reject(FailureReason.EXPIRED);
        }
        if (token.isUsed()) {
            return reject(FailureReason.ALREADY_USED);
        }
        int affected = repository.markUsedIfUnused(token.getId(), Instant.now(clock));
        if (affected == 0) {
            return reject(FailureReason.ALREADY_USED); // lost the atomic race to a concurrent consume
        }
        return ConsumeResult.success(token.getId(), token.getPurpose(), token.getTenantId(),
                token.getUserId(), token.getTarget(), token.getPayload());
    }

    private String hashFor(OneTimeTokenFormat format, UUID tenantId, OneTimeTokenPurpose purpose, String target,
                           String rawSecret) {
        return format == OneTimeTokenFormat.NUMERIC_CODE
                ? numericHash(tenantId, purpose, target, rawSecret)
                : generator.hash(rawSecret);
    }

    /** Scoped hash for low-entropy codes: unique per recipient, and intrinsically tenant/purpose/target-bound. */
    private String numericHash(UUID tenantId, OneTimeTokenPurpose purpose, String target, String code) {
        String normalizedTarget = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        return generator.hash(tenantId + "|" + purpose.name() + "|" + normalizedTarget + "|" + code);
    }

    private ConsumeResult reject(FailureReason reason) {
        // Audit-only signal; the flow collapses every failure to a single generic message (enumeration-safety).
        // TODO(stage-8): emit a ONE_TIME_TOKEN_CONSUME_FAILED audit event (reason={}) via the audit outbox (§7.11).
        log.info("One-time token consume rejected: {}", reason);
        return ConsumeResult.failure(reason);
    }
}
