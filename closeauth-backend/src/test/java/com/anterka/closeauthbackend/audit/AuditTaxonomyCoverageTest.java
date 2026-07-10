package com.anterka.closeauthbackend.audit;

import com.anterka.closeauthbackend.audit.event.AuditEvents;
import com.anterka.closeauthbackend.audit.event.CloseAuthAuditEvent;
import com.anterka.closeauthbackend.audit.enums.AuditEventType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage-8 coverage enforcement, Piece 2 — taxonomy coverage. Catches the harder gap the marker sweep can't: an
 * {@link AuditEventType} that exists in the taxonomy but was never actually wired to fire from anywhere. It ties three
 * things together per event type: (1) a typed factory in {@link AuditEvents} produces it, and (2) that factory is
 * actually invoked at a call site in {@code src/main/java}. If a taxonomy value has no invoked factory, the build fails.
 *
 * <p><b>Documented exclusions</b> — taxonomy values that are deliberately NOT wired yet, each with a reason:
 * <ul>
 *   <li>{@code AGENT_*} — Phase 4; the {@code agents} table is schema-only (no entities to audit).</li>
 *   <li>{@code MFA_ENROLLED}/{@code MFA_REMOVED} — Phase 2; MFA is not built.</li>
 *   <li>{@code CLIENT_UPDATED}/{@code CLIENT_DELETED} — their admin endpoints (client update/delete) are the flagged
 *       7b follow-up, not yet built.</li>
 *   <li>{@code USER_UPDATED} — no generic user-update / profile-PATCH endpoint yet (the other flagged 7b follow-up).</li>
 * </ul>
 * When those surfaces are built, wiring their emission and removing them from this set is the enforced next step.
 */
class AuditTaxonomyCoverageTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** Taxonomy values deliberately un-wired in Phase 1 / Stage 8 (see class Javadoc). */
    private static final Set<AuditEventType> EXCLUSIONS = EnumSet.of(
            AuditEventType.AGENT_REGISTERED,
            AuditEventType.AGENT_REVOKED,
            AuditEventType.AGENT_CONSENT_GRANTED,
            AuditEventType.AGENT_TOKEN_EXCHANGED,
            AuditEventType.MFA_ENROLLED,
            AuditEventType.MFA_REMOVED,
            AuditEventType.CLIENT_UPDATED,
            AuditEventType.CLIENT_DELETED,
            AuditEventType.USER_UPDATED);

    @Test
    void everyNonExcludedEventTypeHasAnInvokedFactory() {
        Map<AuditEventType, Set<String>> typeToFactories = factoryMethodsByEventType();
        Set<String> invokedFactories = factoryNamesInvokedInMainSources();

        Set<AuditEventType> covered = coveredTypes(typeToFactories, invokedFactories);

        List<AuditEventType> missing = new ArrayList<>();
        for (AuditEventType type : AuditEventType.values()) {
            if (!EXCLUSIONS.contains(type) && !covered.contains(type)) {
                missing.add(type);
            }
        }
        assertThat(missing)
                .describedAs("Taxonomy values with no invoked AuditEvents factory (wire an emission or justify an "
                        + "exclusion): %s", missing)
                .isEmpty();
    }

    /** Every exclusion must be a real gap right now — else it is stale and should be removed from the set. */
    @Test
    void exclusionsAreGenuinelyUnwired() {
        Map<AuditEventType, Set<String>> typeToFactories = factoryMethodsByEventType();
        Set<String> invokedFactories = factoryNamesInvokedInMainSources();
        Set<AuditEventType> covered = coveredTypes(typeToFactories, invokedFactories);

        List<AuditEventType> staleExclusions = EXCLUSIONS.stream().filter(covered::contains).toList();
        assertThat(staleExclusions)
                .describedAs("These are excluded but ARE actually wired — remove them from EXCLUSIONS: %s",
                        staleExclusions)
                .isEmpty();
    }

    /**
     * Proves the mechanism has teeth: an un-wired taxonomy value (a known exclusion, e.g. {@code AGENT_REGISTERED}) is
     * genuinely absent from the covered set — so if it were NOT excluded, the primary test above would fail it.
     */
    @Test
    void coverageMechanismDistinguishesWiredFromUnwired() {
        Map<AuditEventType, Set<String>> typeToFactories = factoryMethodsByEventType();
        Set<String> invokedFactories = factoryNamesInvokedInMainSources();
        Set<AuditEventType> covered = coveredTypes(typeToFactories, invokedFactories);

        assertThat(covered)
                .describedAs("A wired type must be detected as covered")
                .contains(AuditEventType.USER_LOGIN_SUCCESS, AuditEventType.TENANT_SUSPENDED);
        assertThat(covered)
                .describedAs("An un-wired (Phase 4) type must NOT be covered — the check's teeth")
                .doesNotContain(AuditEventType.AGENT_REGISTERED);
    }

    // ---- helpers -----------------------------------------------------------

    private static Set<AuditEventType> coveredTypes(Map<AuditEventType, Set<String>> typeToFactories,
                                                    Set<String> invokedFactories) {
        Set<AuditEventType> covered = EnumSet.noneOf(AuditEventType.class);
        typeToFactories.forEach((type, factories) -> {
            if (factories.stream().anyMatch(invokedFactories::contains)) {
                covered.add(type);
            }
        });
        return covered;
    }

    /** Reflectively invokes every {@link AuditEvents} factory with default args to learn which event type it produces. */
    private static Map<AuditEventType, Set<String>> factoryMethodsByEventType() {
        Map<AuditEventType, Set<String>> map = new HashMap<>();
        for (Method method : AuditEvents.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != CloseAuthAuditEvent.class) {
                continue;
            }
            try {
                CloseAuthAuditEvent event = (CloseAuthAuditEvent) method.invoke(null, defaultArgs(method));
                map.computeIfAbsent(event.getEventType(), k -> new HashSet<>()).add(method.getName());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not invoke audit factory " + method.getName(), e);
            }
        }
        return map;
    }

    private static Object[] defaultArgs(Method method) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == int.class || t == long.class || t == short.class || t == byte.class) {
                args[i] = 0;
            } else if (t == boolean.class) {
                args[i] = false;
            } else if (t == double.class || t == float.class) {
                args[i] = 0d;
            } else {
                args[i] = null; // UUID / String / List → null (factories are null-safe)
            }
        }
        return args;
    }

    /** Scans main sources for {@code AuditEvents.<method>(} call sites and returns the invoked factory names. */
    private static Set<String> factoryNamesInvokedInMainSources() {
        Pattern call = Pattern.compile("AuditEvents\\.(\\w+)\\s*\\(");
        Set<String> invoked = new HashSet<>();
        try (Stream<Path> paths = Files.walk(MAIN_SOURCES)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    // Don't count the factory-definition file itself — only genuine call sites.
                    .filter(p -> !p.getFileName().toString().equals("AuditEvents.java"))
                    .forEach(p -> {
                        try {
                            Matcher m = call.matcher(Files.readString(p));
                            while (m.find()) {
                                invoked.add(m.group(1));
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return invoked;
    }
}
