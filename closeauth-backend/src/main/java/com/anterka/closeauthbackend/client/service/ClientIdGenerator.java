package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.common.exception.ClientIdConflictException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Derives the OAuth2 {@code client_id} for a newly-registered client from its display name, mirroring
 * {@code tenant.service.TenantSlugGenerator}'s general shape but simplified for this value's different rules: a
 * {@code client_id} is scoped unique per-tenant (not globally), is never used as a URL path segment (so no reserved
 * word list applies), and a human never has to type or remember it, so a random suffix is appended on every
 * candidate rather than only on collision retry — this keeps the "is it taken" check a defensive backstop instead
 * of the common path.
 *
 * <p>Replaces the pre-registration-wizard behavior of accepting an operator-typed {@code client_id}
 * ({@code RegisterClientCommand} no longer carries one) — same reasoning as {@link ClientSecretGenerator}'s
 * generated secret: the value is opaque, machine-facing, and there is nothing for a human to usefully choose here.
 *
 * <p>Algorithm: lowercase + Unicode-normalise + strip diacritics; collapse runs of non-alphanumeric characters to a
 * single hyphen, trimming leading/trailing hyphens; truncate to 50 characters at a hyphen boundary; fall back to 12
 * random characters if the body is empty (e.g. a non-Latin-script name slugifies to nothing); append an 8-random-
 * character suffix; on collision (checked against the caller-supplied predicate), retry with a fresh suffix up to
 * {@link #MAX_ATTEMPTS} times.
 */
@Component
public class ClientIdGenerator {

    private static final int MAX_BODY_LENGTH = 50;
    private static final int SUFFIX_LENGTH = 8;
    private static final int FALLBACK_LENGTH = 12;
    private static final int MAX_ATTEMPTS = 20;

    private static final String RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final Pattern NON_ALNUM_RUN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("(^-+)|(-+$)");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a unique {@code client_id} derived from {@code clientName}.
     *
     * @param isTaken tests whether a candidate is already in use — bound by the caller to a
     *                tenant-scoped existence check.
     * @throws ClientIdConflictException if no unique candidate could be found within {@link #MAX_ATTEMPTS} attempts
     *                                    (practically unreachable: ~2.8x10^12 combinations per retry).
     */
    public String generate(String clientName, Predicate<String> isTaken) {
        String body = slugify(clientName);
        if (body.isEmpty()) {
            body = randomString(FALLBACK_LENGTH);
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = body + "-" + randomString(SUFFIX_LENGTH);
            if (!isTaken.test(candidate)) {
                return candidate;
            }
        }

        throw new ClientIdConflictException(body);
    }

    private String slugify(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lower = stripped.toLowerCase(Locale.ROOT);
        String hyphenated = NON_ALNUM_RUN.matcher(lower).replaceAll("-");
        String trimmed = LEADING_TRAILING_HYPHENS.matcher(hyphenated).replaceAll("");
        return truncateAtHyphenBoundary(trimmed, MAX_BODY_LENGTH);
    }

    private String truncateAtHyphenBoundary(String body, int maxLength) {
        if (body.length() <= maxLength) {
            return body;
        }
        String cut = body.substring(0, maxLength);
        int lastHyphen = cut.lastIndexOf('-');
        return lastHyphen > 0 ? cut.substring(0, lastHyphen) : cut;
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM_ALPHABET.charAt(secureRandom.nextInt(RANDOM_ALPHABET.length())));
        }
        return sb.toString();
    }
}
