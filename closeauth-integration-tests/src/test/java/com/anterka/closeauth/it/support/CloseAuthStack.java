package com.anterka.closeauth.it.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * The reusable four-container stack the whole integration suite drives: Postgres + Redis + Mailpit + the real CloseAuth
 * backend (built from its existing {@code closeauth-backend/Dockerfile}), wired together on a shared Docker network.
 *
 * <h2>Lifecycle — started ONCE per JVM (singleton), never explicitly stopped</h2>
 * Container startup is slow, so the stack is a lazily-initialized singleton ({@link #getStarted()}): the FIRST test to
 * ask for it boots everything; every later test/class reuses it. Nothing stops the containers — Testcontainers' Ryuk
 * sidecar reaps them when the JVM exits. A fresh {@code mvn test} run therefore always gets fresh containers (empty DB,
 * Flyway re-migrated), so <b>a second run is never polluted by the first</b>. Within a run, tests must isolate via
 * unique fixture data ({@link Fixtures}), not by assuming a clean DB between methods.
 *
 * <h2>App image + the jar prerequisite</h2>
 * The backend image is built from the <b>existing</b> {@code Dockerfile}, which is jar-based
 * ({@code COPY target/*.jar}). So {@code closeauth-backend/target/*.jar} must exist before this runs — see
 * {@link #locateBackendJar()} for the fail-fast message and README for the build command.
 *
 * <h2>Config wiring</h2>
 * The app runs the {@code docker} profile and is pointed at the sibling containers by their network aliases. Mail is
 * pointed at Mailpit with SMTP AUTH/STARTTLS disabled (Mailpit rejects both by default); the platform-admin bootstrap
 * credential is injected so a known admin exists in the fresh container. See {@link #buildAppContainer}.
 */
public final class CloseAuthStack {

    private static final Logger log = LoggerFactory.getLogger(CloseAuthStack.class);

    // Versions match the repo's docker-compose.yml (Postgres 17, Redis 8) so tests run against what production runs.
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:8-alpine");
    // No official Testcontainers Mailpit module exists; use GenericContainer on the official image.
    private static final DockerImageName MAILPIT_IMAGE = DockerImageName.parse("axllent/mailpit:v1.21.8");

    private static final String DB_NAME = "closeauth";
    private static final String DB_USER = "closeauth";
    private static final String DB_PASSWORD = "closeauth";

    private static final String CONTEXT_PATH = "/closeauth";
    private static final int APP_PORT = 9000;                 // docker profile SERVER_PORT default + Dockerfile EXPOSE
    private static final int MAILPIT_SMTP_PORT = 1025;
    private static final int MAILPIT_HTTP_PORT = 8025;

    /** A known platform admin, bootstrapped into the fresh container from config (same mechanism the app uses). */
    public static final String BOOTSTRAP_ADMIN_EMAIL = "it-platform-admin@closeauth.test";
    public static final String BOOTSTRAP_ADMIN_PASSWORD = "It-Bootstrap-Pw-123!";

    private static volatile CloseAuthStack instance;

    private final PostgreSQLContainer<?> postgres;
    private final GenericContainer<?> redis;
    private final GenericContainer<?> mailpit;
    private final GenericContainer<?> app;

    private CloseAuthStack() {
        Network network = Network.newNetwork();

        this.postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withDatabaseName(DB_NAME)
                .withUsername(DB_USER)
                .withPassword(DB_PASSWORD);

        this.redis = new GenericContainer<>(REDIS_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("redis")
                .withExposedPorts(6379)
                .waitingFor(Wait.forListeningPort());

        this.mailpit = new GenericContainer<>(MAILPIT_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("mailpit")
                .withExposedPorts(MAILPIT_SMTP_PORT, MAILPIT_HTTP_PORT)
                .waitingFor(Wait.forListeningPort());

        // Infra first — the app's health check depends on Postgres + Redis being up.
        log.info("Starting Postgres + Redis + Mailpit...");
        Startables.deepStart(postgres, redis, mailpit).join();

        this.app = buildAppContainer(network);
        log.info("Building + starting the CloseAuth backend image (from closeauth-backend/Dockerfile)...");
        app.start();
        log.info("CloseAuth stack ready. App at {}", appBaseUri());
    }

    /** Boots the stack on first call; returns the shared instance thereafter. Thread-safe. */
    public static synchronized CloseAuthStack getStarted() {
        if (instance == null) {
            instance = new CloseAuthStack();
        }
        return instance;
    }

    /**
     * Whether the backend boot jar exists yet (the jar-based Dockerfile needs it). The base class turns a missing jar
     * into a skipped-with-guidance test rather than an error, so a plain {@code mvn} build (which doesn't package the
     * backend) stays green; the documented run command packages the backend first.
     */
    public static boolean backendJarPresent() {
        try {
            locateBackendJar(backendModuleDir());
            return true;
        } catch (RuntimeException notFound) {
            return false;
        }
    }

    private GenericContainer<?> buildAppContainer(Network network) {
        Path backend = backendModuleDir();
        ImageFromDockerfile image = new ImageFromDockerfile("closeauth-backend-it:latest", false)
                .withFileFromPath("Dockerfile", backend.resolve("Dockerfile"))
                .withFileFromPath("entrypoint.sh", backend.resolve("entrypoint.sh"))
                // The Dockerfile's `COPY target/*.jar /app.jar` matches this single jar placed in the build context.
                .withFileFromPath("target/app.jar", locateBackendJar(backend));

        return new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases("closeauth-backend")
                .withExposedPorts(APP_PORT)
                // --- datastores (by in-network alias, not localhost) ---
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://postgres:5432/" + DB_NAME)
                .withEnv("SPRING_DATASOURCE_USERNAME", DB_USER)
                .withEnv("SPRING_DATASOURCE_PASSWORD", DB_PASSWORD)
                .withEnv("SPRING_DATA_REDIS_HOST", "redis")
                .withEnv("SPRING_DATA_REDIS_PORT", "6379")
                // --- mail → Mailpit (AUTH + STARTTLS off; Mailpit rejects both by default) ---
                .withEnv("SMTP_HOST", "mailpit")
                .withEnv("SMTP_PORT", String.valueOf(MAILPIT_SMTP_PORT))
                .withEnv("SMTP_USERNAME", "")
                .withEnv("SMTP_PASSWORD", "")
                .withEnv("SMTP_AUTH", "false")
                .withEnv("SMTP_STARTTLS", "false")
                // Don't make the app's health depend on an SMTP handshake — the journey verifies Mailpit directly.
                .withEnv("MANAGEMENT_HEALTH_MAIL_ENABLED", "false")
                // Profile is set explicitly (not via SPRING_PROFILE) to avoid entrypoint.sh's no-space concat quirk;
                // bootstrap creds go via -D so relaxed env-var binding of the hyphenated property can't bite us.
                .withEnv("JAVA_OPTS", String.join(" ",
                        "-Dspring.profiles.active=docker",
                        "-Dcloseauth.platform-admin.bootstrap-email=" + BOOTSTRAP_ADMIN_EMAIL,
                        "-Dcloseauth.platform-admin.bootstrap-password=" + BOOTSTRAP_ADMIN_PASSWORD))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("closeauth-backend")))
                .waitingFor(Wait.forHttp(CONTEXT_PATH + "/actuator/health")
                        .forPort(APP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(4)));
    }

    // ---- accessors exposed to test classes --------------------------------

    /** Base URI of the app (host + mapped port), WITHOUT the context path. */
    public String appBaseUri() {
        return "http://" + app.getHost() + ":" + app.getMappedPort(APP_PORT);
    }

    /** The servlet context path every route is served under. */
    public String contextPath() {
        return CONTEXT_PATH;
    }

    /** A Mailpit REST client pointed at the running Mailpit container's mapped HTTP port. */
    public MailpitClient mailpit() {
        return new MailpitClient("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(MAILPIT_HTTP_PORT));
    }

    /** A read-only JDBC helper pointed at the Postgres container's mapped port. */
    public Db db() {
        return new Db(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    // ---- backend image inputs ---------------------------------------------

    private static Path backendModuleDir() {
        for (Path candidate : List.of(Path.of("..", "closeauth-backend"), Path.of("closeauth-backend"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        // Default to the sibling layout; locateBackendJar will produce the actionable error if it's wrong.
        return Path.of("..", "closeauth-backend");
    }

    private static Path locateBackendJar(Path backend) {
        Path targetDir = backend.resolve("target");
        try (Stream<Path> files = Files.list(targetDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-plain.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-sources.jar"))
                    .filter(p -> !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .findFirst()
                    .orElseThrow(() -> jarMissing(targetDir));
        } catch (IOException e) {
            throw jarMissing(targetDir);
        }
    }

    private static IllegalStateException jarMissing(Path targetDir) {
        return new IllegalStateException(
                "Backend boot jar not found in " + targetDir.toAbsolutePath() + ". The integration suite builds the "
                        + "app image from closeauth-backend/Dockerfile (which is jar-based), so the backend must be "
                        + "packaged first. From the repo root run:\n"
                        + "    mvn -pl closeauth-backend -am package -DskipTests\n"
                        + "or run the whole thing with:  mvn -pl closeauth-backend,closeauth-integration-tests package");
    }
}
