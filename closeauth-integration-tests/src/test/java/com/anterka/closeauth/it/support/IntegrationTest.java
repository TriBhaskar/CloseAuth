package com.anterka.closeauth.it.support;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Base class every integration journey extends. It boots the shared {@link CloseAuthStack} once (JVM-wide singleton)
 * and points REST Assured at the running app. If Docker is not available the whole suite is <b>skipped</b> (an
 * assumption, not a failure) so a Docker-less {@code mvn} build stays green — the tests only run where they can.
 *
 * <p>Exposes the three things journeys need: the app (via REST Assured's configured base URI + context path), the
 * {@link MailpitClient}, and the read-only {@link Db}, plus a {@link #platformAdminToken()} helper (the bootstrap
 * platform-admin credential minted into a bearer token).
 */
public abstract class IntegrationTest {

    protected static CloseAuthStack stack;

    @BeforeAll
    static void bootStack() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — skipping integration tests (they require Docker to run the containers).");
        assumeTrue(CloseAuthStack.backendJarPresent(),
                "Backend boot jar not built — skipping. Package it first: "
                        + "'mvn -pl closeauth-backend -am package -DskipTests' (see the module README).");
        stack = CloseAuthStack.getStarted();
        // Every request in a journey is relative to the app's base URI + servlet context path (/closeauth).
        RestAssured.baseURI = stack.appBaseUri();
        RestAssured.basePath = stack.contextPath();
    }

    protected static MailpitClient mailpit() {
        return stack.mailpit();
    }

    protected static Db db() {
        return stack.db();
    }

    /** The OAuth2 Authorization Code + PKCE flow client (IT-2) — reused by every flow-driven journey. */
    protected static OAuthFlowClient oauthFlow() {
        return stack.oauthFlow();
    }

    /** The bearer-auth admin API client (IT-3) — reused by every admin-surface / RBAC journey. */
    protected static AdminApiClient adminApi() {
        return stack.adminApi();
    }

    /** Mints a platform-admin bearer token from the bootstrap credential ({@code POST /v1/platform/auth/token}). */
    protected static String platformAdminToken() {
        return RestAssured.given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "email", CloseAuthStack.BOOTSTRAP_ADMIN_EMAIL,
                        "password", CloseAuthStack.BOOTSTRAP_ADMIN_PASSWORD))
                .when()
                .post("/v1/platform/auth/token")
                .then()
                .statusCode(200)
                .extract()
                .path("access_token");
    }
}
