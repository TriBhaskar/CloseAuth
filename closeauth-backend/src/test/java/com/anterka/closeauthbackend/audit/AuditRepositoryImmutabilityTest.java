package com.anterka.closeauthbackend.audit;

import com.anterka.closeauthbackend.audit.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage-8 append-only enforcement at the interface level (§7.11). {@link AuditEventRepository} must expose NO
 * update/delete surface — application code cannot tamper with the audit log through it even if compromised. This is a
 * compile-time-ish guarantee made explicit: the ONLY mutating method is {@code save} (an INSERT, since ids are
 * DB-generated and never reused); everything else is a read. Retention deletion lives on a separate, clearly-named
 * repository, not here.
 */
class AuditRepositoryImmutabilityTest {

    @Test
    void auditEventRepositoryExposesNoDeleteOrUpdateSurface() {
        List<String> mutators = Arrays.stream(AuditEventRepository.class.getMethods())
                .map(Method::getName)
                .filter(AuditRepositoryImmutabilityTest::looksMutating)
                .toList();
        assertThat(mutators)
                .describedAs("AuditEventRepository must be append-only — no delete/remove/update methods, found: %s",
                        mutators)
                .isEmpty();
    }

    @Test
    void auditEventRepositoryDoesNotInheritFullCrud() {
        assertThat(CrudRepository.class.isAssignableFrom(AuditEventRepository.class))
                .describedAs("AuditEventRepository must NOT extend CrudRepository (which exposes deleteById/deleteAll)")
                .isFalse();
        assertThat(JpaRepository.class.isAssignableFrom(AuditEventRepository.class))
                .describedAs("AuditEventRepository must NOT extend JpaRepository (full CRUD surface)")
                .isFalse();
    }

    private static boolean looksMutating(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("delete") || n.startsWith("remove") || n.startsWith("update")
                || n.startsWith("truncate");
    }
}
