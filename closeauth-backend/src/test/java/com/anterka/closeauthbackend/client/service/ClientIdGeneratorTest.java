package com.anterka.closeauthbackend.client.service;

import com.anterka.closeauthbackend.common.exception.ClientIdConflictException;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link ClientIdGenerator} — no Spring context, no DB. {@code isTaken} is a plain predicate,
 * exercised directly. Unlike {@code TenantSlugGenerator}, there is no reserved-word list and no {@code ten_}
 * prefix — a {@code client_id} is never a URL path segment, so it has neither concern.
 */
class ClientIdGeneratorTest {

    private final ClientIdGenerator generator = new ClientIdGenerator();

    private static final Predicate<String> NEVER_TAKEN = s -> false;

    @Test
    void lowercasesStripsDiacriticsAndAppendsARandomSuffix() {
        String result = generator.generate("Acmé Inc.", NEVER_TAKEN);
        assertThat(result).startsWith("acme-inc-");
        // body-8char suffix, hyphen-joined
        assertThat(result).matches("acme-inc-[a-z0-9]{8}");
    }

    @Test
    void collapsesNonAlphanumericRunsToASingleHyphenAndTrimsEnds() {
        String result = generator.generate("  Foo   Bar!!Baz--", NEVER_TAKEN);
        assertThat(result).startsWith("foo-bar-baz-");
    }

    @Test
    void truncatesTo50CharactersAtAHyphenBoundary() {
        String name = "aaaaa-bbbbb-ccccc-ddddd-eeeee-fffff-ggggg-hhhhh-iiiii-jjjjj"; // well over 50 chars
        String result = generator.generate(name, NEVER_TAKEN);
        int suffixStart = result.lastIndexOf('-');
        String body = result.substring(0, suffixStart);
        assertThat(body.length()).isLessThanOrEqualTo(50);
    }

    @Test
    void hardTruncatesAt50WhenNoHyphenExistsInRange() {
        String name = "a".repeat(80); // one long word, no hyphens at all
        String result = generator.generate(name, NEVER_TAKEN);
        assertThat(result).startsWith("a".repeat(50) + "-");
    }

    @Test
    void fallsBackToATwelveCharacterRandomBodyWhenSlugificationProducesNothing() {
        // Entirely non-Latin script: no a-z0-9 characters survive, body collapses to empty.
        String result = generator.generate("日本語", NEVER_TAKEN);
        int suffixStart = result.lastIndexOf('-');
        String body = result.substring(0, suffixStart);
        assertThat(body).hasSize(12);
        assertThat(body).matches("[a-z0-9]{12}");
    }

    @Test
    void aBareNameNeverCollidesWithItsOwnGeneratedIdBecauseASuffixIsAlwaysAppended() {
        // Even with nothing "taken," the returned id is never the bare slugified name — the random
        // suffix is unconditional, not retry-only (unlike TenantSlugGenerator's bare-first attempt).
        String result = generator.generate("Acme Inc", NEVER_TAKEN);
        assertThat(result).isNotEqualTo("acme-inc");
        assertThat(result).startsWith("acme-inc-");
    }

    @Test
    void retriesWithAFreshSuffixOnCollisionUntilAnUntakenCandidateIsFound() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        // First two attempts are "taken"; the third succeeds.
        String result = generator.generate("Acme Inc", s -> calls.incrementAndGet() <= 2);
        assertThat(result).startsWith("acme-inc-");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void suffixedRetriesAreRandomAndTypicallyDistinct() {
        Set<String> results = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            results.add(generator.generate("Acme Inc", NEVER_TAKEN));
        }
        assertThat(results).hasSizeGreaterThan(15);
    }

    @Test
    void throwsAfterExhaustingTheRetryBudgetWhenEveryCandidateIsTaken() {
        assertThatThrownBy(() -> generator.generate("Acme Inc", s -> true))
                .isInstanceOf(ClientIdConflictException.class);
    }
}
