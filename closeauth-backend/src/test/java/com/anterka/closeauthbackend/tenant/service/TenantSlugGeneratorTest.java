package com.anterka.closeauthbackend.tenant.service;

import com.anterka.closeauthbackend.common.exception.TenantSlugConflictException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link TenantSlugGenerator} — no Spring context, no DB. {@code isTaken} is
 * a plain predicate, exercised directly against the ground rules in spec §1.2.
 */
class TenantSlugGeneratorTest {

    private final TenantSlugGenerator generator = new TenantSlugGenerator();

    private static final java.util.function.Predicate<String> NEVER_TAKEN = s -> false;

    @Test
    void lowercasesAndStripsDiacritics() {
        assertThat(generator.generate("Acmé Inc.", NEVER_TAKEN)).isEqualTo("ten_acme-inc");
    }

    @Test
    void collapsesNonAlphanumericRunsToASingleHyphenAndTrimsEnds() {
        assertThat(generator.generate("  Foo   Bar!!Baz--", NEVER_TAKEN)).isEqualTo("ten_foo-bar-baz");
    }

    @Test
    void truncatesTo40CharactersAtAHyphenBoundary() {
        // 5-char words joined by hyphens: body would be 41 words * 6 - 1 = way over 40 chars if
        // untruncated. Construct a name whose slugified body has a hyphen just before char 40.
        String name = "aaaaa-bbbbb-ccccc-ddddd-eeeee-fffff-ggggg-hhhhh"; // 48 chars, hyphens at 5,11,17,23,29,35,41
        String result = generator.generate(name, NEVER_TAKEN);
        String body = result.substring("ten_".length());
        assertThat(body.length()).isLessThanOrEqualTo(40);
        // Cut lands mid-"hhhhh" (chars 41-45) at position 40 -> backs off to the last hyphen at 35.
        assertThat(body).isEqualTo("aaaaa-bbbbb-ccccc-ddddd-eeeee-fffff");
    }

    @Test
    void hardTruncatesAt40WhenNoHyphenExistsInRange() {
        String name = "a".repeat(60); // one long word, no hyphens at all
        String result = generator.generate(name, NEVER_TAKEN);
        assertThat(result).isEqualTo("ten_" + "a".repeat(40));
    }

    @Test
    void fallsBackToAnEightCharacterRandomBodyWhenSlugificationProducesNothing() {
        // Entirely non-Latin script: no a-z0-9 characters survive, body collapses to empty.
        String result = generator.generate("日本語", NEVER_TAKEN);
        assertThat(result).startsWith("ten_");
        String body = result.substring("ten_".length());
        assertThat(body).hasSize(8);
        assertThat(body).matches("[a-z2-7]{8}");
    }

    @Test
    void reservedWordsGetACollisionSuffixRatherThanBeingUsedBare() {
        String result = generator.generate("Admin", NEVER_TAKEN);
        assertThat(result).startsWith("ten_admin-");
        assertThat(result).isNotEqualTo("ten_admin");
    }

    @Test
    void everyReservedWordTriggersASuffix() {
        for (String reserved : Set.of("platform", "admin", "api", "auth", "oauth2", "login", "logout",
                "console", "account", "t", "www", "static", "health", "closeauth")) {
            String result = generator.generate(reserved, NEVER_TAKEN);
            assertThat(result).as(reserved).isNotEqualTo("ten_" + reserved);
            assertThat(result).as(reserved).startsWith("ten_" + reserved + "-");
        }
    }

    @Test
    void aSingleCharacterBodyIsTreatedAsReservedRegardlessOfValue() {
        String result = generator.generate("Z", NEVER_TAKEN);
        assertThat(result).isNotEqualTo("ten_z");
        assertThat(result).startsWith("ten_z-");
    }

    @Test
    void retriesWithASuffixOnCollisionUntilAnUntakenCandidateIsFound() {
        AtomicInteger calls = new AtomicInteger();
        // The bare candidate and the first two suffixed attempts are "taken"; the third succeeds.
        String result = generator.generate("Acme Inc", s -> calls.incrementAndGet() <= 3);
        assertThat(result).startsWith("ten_acme-inc-");
        assertThat(calls.get()).isEqualTo(4); // 1 bare check + 3 taken suffix attempts
    }

    @Test
    void suffixedRetriesAreRandomAndTypicallyDistinct() {
        // Forces every call through the suffix path (only the bare candidate is ever "taken"),
        // then checks the random suffixes aren't repeating — a real randomness smoke test, not a
        // strict uniqueness guarantee (a 4-char, 32-symbol suffix over 20 draws has a
        // vanishingly small chance of an incidental repeat).
        java.util.function.Predicate<String> onlyBareIsTaken = "ten_acme-inc"::equals;
        Set<String> results = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            results.add(generator.generate("Acme Inc", onlyBareIsTaken));
        }
        assertThat(results).hasSizeGreaterThan(15);
    }

    @Test
    void throwsAfterExhaustingTheRetryBudgetWhenEveryCandidateIsTaken() {
        assertThatThrownBy(() -> generator.generate("Acme Inc", s -> true))
                .isInstanceOf(TenantSlugConflictException.class);
    }
}
