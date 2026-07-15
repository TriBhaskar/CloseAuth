package com.anterka.closeauth.it.support;

import java.util.Locale;
import java.util.UUID;

/**
 * Unique fixture data — THE isolation mechanism for this suite. Containers are shared across test methods (they're slow
 * to boot), so tests must NOT assume a clean database between methods; instead each test creates its own fresh tenant /
 * user / client with a randomized suffix so tests never collide or depend on execution order. Every future test class
 * must follow this convention (documented in the module README).
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** A short, unique, DNS/identifier-safe suffix. */
    public static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toLowerCase(Locale.ROOT);
    }

    /** A unique, lowercase email (the backend normalizes emails to lowercase, so DB lookups match). */
    public static String email(String local) {
        return (local + "-" + suffix() + "@example.test").toLowerCase(Locale.ROOT);
    }

    /** A unique tenant slug (lowercase alphanumerics + hyphen, matching the backend's slug validation). */
    public static String slug(String base) {
        return (base + "-" + suffix()).toLowerCase(Locale.ROOT);
    }

    /** A unique OAuth2 client id. */
    public static String clientId(String base) {
        return base + "-" + suffix();
    }
}
