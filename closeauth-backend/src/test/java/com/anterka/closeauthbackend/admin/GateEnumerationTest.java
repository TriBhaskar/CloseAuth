package com.anterka.closeauthbackend.admin;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE most important test in Stage 7b — the deny-by-default enforcement. It discovers every {@code /v1/**} handler
 * method across all {@code @RestController}s and asserts each carries an authorization gate ({@code @RequiresPlatformAdmin}
 * / {@code @RequiresTenantAccess} / {@code @RequiresSelf}, or an explicit {@code @PreAuthorize}) on the method or its
 * declaring class. A newly-added endpoint that forgets its gate FAILS this test — turning "forgot to gate an endpoint"
 * from a silent production hole into a red build.
 *
 * <p>Runs by pure reflection (no Spring context) so it ALWAYS runs in {@code mvn test}. The one intentional exception is
 * the documented, {@code permitAll} platform-admin token-mint endpoint ({@code /v1/platform/auth/**}), which authenticates
 * via body credentials (there is no bearer yet).
 */
class GateEnumerationTest {

    private static final String BASE_PACKAGE = "com.anterka.closeauthbackend";
    /** The ONE intentionally-unauthenticated {@code /v1} path (token mint; authenticates via body creds). */
    private static final String PUBLIC_ALLOWLIST_PREFIX = "/v1/platform/auth/";

    @Test
    void everyV1EndpointIsGated() {
        List<String> ungated = new ArrayList<>();
        for (Class<?> controller : restControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                for (String path : v1PathsOf(controller, method)) {
                    if (path.startsWith(PUBLIC_ALLOWLIST_PREFIX)) {
                        continue; // documented public exception
                    }
                    if (!isGated(method, controller)) {
                        ungated.add(controller.getSimpleName() + "#" + method.getName() + " -> " + path);
                    }
                }
            }
        }
        assertThat(ungated)
                .describedAs("Ungated /v1 endpoints (add @RequiresPlatformAdmin/@RequiresTenantAccess/@RequiresSelf): %s",
                        ungated)
                .isEmpty();
    }

    /** Proves the check has teeth: a synthetic ungated /v1 endpoint MUST be flagged as a violation. */
    @Test
    void detectsAnUngatedEndpoint() {
        Method ungated = firstMethod(UngatedSample.class, "leak");
        Method gated = firstMethod(GatedSample.class, "safe");

        assertThat(v1PathsOf(UngatedSample.class, ungated)).isNotEmpty();
        assertThat(isGated(ungated, UngatedSample.class)).isFalse(); // would FAIL the build — as intended
        assertThat(isGated(gated, GatedSample.class)).isTrue();
    }

    // ---- discovery + predicate --------------------------------------------

    private List<Class<?>> restControllers() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> classes = new ArrayList<>();
        scanner.findCandidateComponents(BASE_PACKAGE).forEach(bd -> {
            String name = bd.getBeanClassName();
            if (name == null || name.contains("$")) {
                return; // skip nested classes (e.g. this test's synthetic samples) — real controllers are top-level
            }
            try {
                classes.add(Class.forName(name));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        });
        return classes;
    }

    /** The full paths of {@code method} that start with {@code /v1/} (class prefix + method path). */
    private List<String> v1PathsOf(Class<?> controller, Method method) {
        RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (methodMapping == null) {
            return List.of(); // not a handler method
        }
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String[] classPaths = pathsOrEmpty(classMapping);
        String[] methodPaths = pathsOrEmpty(methodMapping);
        List<String> full = new ArrayList<>();
        for (String cp : classPaths) {
            for (String mp : methodPaths) {
                String path = cp + mp;
                if (path.startsWith("/v1/")) {
                    full.add(path);
                }
            }
        }
        return full;
    }

    private static String[] pathsOrEmpty(RequestMapping mapping) {
        if (mapping == null) {
            return new String[]{""};
        }
        String[] paths = mapping.path();
        return paths.length == 0 ? new String[]{""} : paths;
    }

    /** Gated iff a security annotation (meta-)present on the method OR its declaring class — @PreAuthorize covers all our markers. */
    private boolean isGated(Method method, Class<?> controller) {
        return AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(controller, PreAuthorize.class);
    }

    private static Method firstMethod(Class<?> type, String name) {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new IllegalStateException("no method " + name);
    }

    // ---- synthetic samples for the teeth self-test ------------------------

    @RestController
    @RequestMapping("/v1/danger")
    static class UngatedSample {
        @GetMapping("/leak")
        String leak() {
            return "reachable by any authenticated caller — the exact hole this test prevents";
        }
    }

    @RestController
    @RequestMapping("/v1/safe")
    @PreAuthorize("isAuthenticated()")
    static class GatedSample {
        @GetMapping("/safe")
        String safe() {
            return "gated";
        }
    }
}
