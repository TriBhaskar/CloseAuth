package com.anterka.closeauth.it.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extractors for the one-time secrets CloseAuth emails carry. IT-1 pulls a 6-digit numeric code out of the
 * verification email; the link-based flows (magic-link, password-reset) instead carry the secret as a {@code token}
 * URL query parameter inside a link in the body. This isolates that "get the token out of the email link" parsing so
 * every link-based flow shares it. (The link's host/port is the app's configured issuer/BFF base and does NOT match
 * the container's mapped port, so tests use only the extracted token, not the link URL itself.)
 */
public final class Emails {

    private Emails() {
    }

    /** Extracts the {@code token} link query-param value (magic-link / password-reset link). */
    public static String linkToken(String emailBody) {
        return linkParam(emailBody, "token");
    }

    /**
     * Extracts a named link query-param value from an email body. Different flows use different param names —
     * magic-link/reset links carry {@code token=}, an invite link carries {@code invite=}.
     */
    public static String linkParam(String emailBody, String paramName) {
        Pattern pattern = Pattern.compile("[?&]" + Pattern.quote(paramName) + "=([^&\\s\"'<>]+)");
        Matcher matcher = pattern.matcher(emailBody == null ? "" : emailBody);
        if (!matcher.find()) {
            throw new IllegalStateException("No '" + paramName + "=' link parameter found in the email body");
        }
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}
