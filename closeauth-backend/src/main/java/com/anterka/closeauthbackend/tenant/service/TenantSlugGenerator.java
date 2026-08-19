package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.exception.TenantSlugConflictException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Derives the public, {@code ten_}-prefixed Tenant ID from an operator-supplied name, per
 * {@code CLOSEAUTH_FRONTEND_SPEC.md} §1.2. The operator never types or edits the ID directly —
 * see {@link com.anterka.closeauthbackend.tenant.dto.ProvisionTenantCommand}.
 *
 * <p>Algorithm: lowercase + Unicode-normalise + strip diacritics; collapse runs of
 * non-alphanumeric characters to a single hyphen, trimming leading/trailing hyphens; truncate to
 * 40 characters at a hyphen boundary; fall back to 8 random characters if the body is empty
 * (e.g. a non-Latin-script name slugifies to nothing); reject the reserved list and any
 * single-character body as a collision; on collision, retry with a 4-random-character suffix
 * appended to the original body, up to {@link #MAX_ATTEMPTS} times.
 */
@Component
public class TenantSlugGenerator {

    private static final String PREFIX = "ten_";
    private static final int MAX_BODY_LENGTH = 40;
    private static final int FALLBACK_LENGTH = 8;
    private static final int SUFFIX_LENGTH = 4;
    private static final int MAX_ATTEMPTS = 20;

    // Lowercase base32-shaped alphabet (a-z2-7), matching spec §1.2's own example suffix
    // ("7f3a") — no 0/1/8/9, avoiding visual ambiguity with o/i/b/z.
    private static final String RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    private static final Set<String> RESERVED = Set.of(
            "platform", "admin", "api", "auth", "oauth2", "login", "logout",
            "console", "account", "t", "www", "static", "health", "closeauth");

    private static final Pattern NON_ALNUM_RUN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern LEADING_TRAILING_HYPHENS = Pattern.compile("(^-+)|(-+$)");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a unique, {@code ten_}-prefixed Tenant ID for {@code name}.
     *
     * @param isTaken tests whether a candidate (full, {@code ten_}-prefixed) value is already in
     *                use — bound by the caller to a repository existence check.
     * @throws TenantSlugConflictException if no unique candidate could be found within
     *                                      {@link #MAX_ATTEMPTS} attempts (practically unreachable)
     */
    public String generate(String name, Predicate<String> isTaken) {
        String body = slugify(name);
        if (body.isEmpty()) {
            body = randomString(FALLBACK_LENGTH);
        }

        String candidate = PREFIX + body;
        if (!isReserved(body) && !isTaken.test(candidate)) {
            return candidate;
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String suffixed = body + "-" + randomString(SUFFIX_LENGTH);
            candidate = PREFIX + suffixed;
            // A suffixed body can never exactly match a (short, fixed) reserved word, so only
            // the taken-check applies here.
            if (!isTaken.test(candidate)) {
                return candidate;
            }
        }

        throw new TenantSlugConflictException(candidate);
    }

    private String slugify(String name) {
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFKD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lower = stripped.toLowerCase(java.util.Locale.ROOT);
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

    private boolean isReserved(String body) {
        return body.length() == 1 || RESERVED.contains(body);
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM_ALPHABET.charAt(secureRandom.nextInt(RANDOM_ALPHABET.length())));
        }
        return sb.toString();
    }
}
