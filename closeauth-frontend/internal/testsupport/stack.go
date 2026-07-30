// Package testsupport is the Go equivalent of the Java integration-test
// module's support package (closeauth-integration-tests/.../it/support): a
// reusable Testcontainers-go harness that boots the REAL backend (built from
// its actual Dockerfile) alongside real Postgres, Redis, and Mailpit, so
// tests in internal/backend and internal/proxy can drive real round trips
// instead of asserting behavior from reading code.
//
// This is intentionally a plain importable package, not *_test.go files —
// multiple test packages (internal/backend, internal/proxy, and later UI-2/3/4
// packages) need to share it, and Go can't import another package's _test.go
// helpers across package boundaries. Because nothing outside a _test.go file
// imports testsupport, none of this (nor the testcontainers-go dependency
// tree it pulls in) reaches the production binary built by cmd/api.
package testsupport

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/docker/docker/api/types/container"
	"github.com/docker/go-connections/nat"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/modules/redis"
	tcnetwork "github.com/testcontainers/testcontainers-go/network"
	"github.com/testcontainers/testcontainers-go/wait"

	"closeauth-frontend/internal/backend"
)

// Image versions + in-network aliases. Verified against docker-compose.yml
// (Postgres 17-alpine, Redis 8-alpine) and closeauth-integration-tests'
// CloseAuthStack.java (same versions, plus the Mailpit choice) — both still
// accurate as of this stage; see the stage report for the verification.
const (
	postgresImage = "postgres:17-alpine"
	redisImage    = "redis:8-alpine"
	mailpitImage  = "axllent/mailpit:v1.21.8"

	dbName     = "closeauth"
	dbUser     = "closeauth"
	dbPassword = "closeauth"

	appContextPath  = "/closeauth"
	appPort         = "9000/tcp"
	mailpitSMTPPort = "1025/tcp"
	mailpitHTTPPort = "8025/tcp"

	// BootstrapAdminEmail / BootstrapAdminPassword are injected into the app
	// container via the SAME config keys the Java IT module used
	// (closeauth.platform-admin.bootstrap-email/-password — confirmed still
	// current against CloseAuthProperties.PlatformAdmin), so a known platform
	// admin exists on every fresh container.
	BootstrapAdminEmail    = "ui-platform-admin@closeauth.test"
	BootstrapAdminPassword = "Ui-Bootstrap-Pw-123!"
)

// Stack is the four-container harness: Postgres + Redis + Mailpit + the real
// CloseAuth backend, wired together on a private Docker network.
type Stack struct {
	network  *testcontainers.DockerNetwork
	postgres *postgres.PostgresContainer
	redis    *redis.RedisContainer
	mailpit  testcontainers.Container
	app      testcontainers.Container

	appHost string
	appPort int
}

var (
	once     sync.Once
	instance *Stack
	bootErr  error
)

// Get boots the shared Stack on first call within this test process and
// returns the cached instance thereafter (container startup is slow, so
// every test in the package shares one stack — the Go-process-scoped
// equivalent of the Java module's JVM-wide singleton). Nothing terminates the
// containers explicitly; testcontainers-go's Ryuk reaper cleans them up when
// the test process exits, so a fresh `go test` run always gets fresh
// containers and a within-run test isolates via Fixtures, not a clean DB.
//
// Skips (not fails) the calling test if Docker is unavailable or the backend
// jar hasn't been packaged yet, mirroring the Java module's assumeTrue-based
// graceful skip — a Docker-less or unpackaged `go test ./...` stays green.
func Get(tb testing.TB) *Stack {
	tb.Helper()
	once.Do(func() {
		if !BackendJarPresent() {
			bootErr = fmt.Errorf("backend boot jar not built — from the repo root run: " +
				"mvn -pl closeauth-backend -am package -DskipTests")
			return
		}
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Minute)
		defer cancel()
		instance, bootErr = boot(ctx)
	})
	if bootErr != nil {
		tb.Skipf("CloseAuth stack unavailable, skipping: %v", bootErr)
	}
	return instance
}

// AppBaseURI is the app's base URI (host + Docker-assigned mapped port),
// WITHOUT the context path.
func (s *Stack) AppBaseURI() string {
	return fmt.Sprintf("http://%s:%d", s.appHost, s.appPort)
}

// ContextPath is the servlet context path every backend route is served
// under.
func (s *Stack) ContextPath() string {
	return appContextPath
}

// OAuthClient returns a backend.OAuthClient pointed at the running app.
func (s *Stack) OAuthClient(redirectURI string) *backend.OAuthClient {
	return backend.NewOAuthClient(s.AppBaseURI(), s.ContextPath(), redirectURI)
}

// AdminClient returns a backend.AdminClient pointed at the running app.
func (s *Stack) AdminClient() *backend.AdminClient {
	return backend.NewAdminClient(s.AppBaseURI(), s.ContextPath())
}

// MailpitBaseURI returns the running Mailpit container's REST API base URL.
// Included in the harness since UI-1 (documented decision: UI-2 needs
// Mailpit for registration/verification/magic-link/reset journeys, and
// wiring it into the harness later would mean redesigning this file
// mid-stage-sequence for a container that costs almost nothing to include
// today). Stage UI-2b adds the first typed client over this URL — see
// Stack.Mailpit / mailpit.go — since it's now the stage that actually needs
// one (EMAIL_VERIFIED + INVITE_ONLY registration journeys).
func (s *Stack) MailpitBaseURI(ctx context.Context) (string, error) {
	host, err := s.mailpit.Host(ctx)
	if err != nil {
		return "", fmt.Errorf("mailpit host: %w", err)
	}
	port, err := s.mailpit.MappedPort(ctx, nat.Port(mailpitHTTPPort))
	if err != nil {
		return "", fmt.Errorf("mailpit mapped port: %w", err)
	}
	return fmt.Sprintf("http://%s:%d", host, port.Int()), nil
}

func boot(ctx context.Context) (*Stack, error) {
	backendDir, err := locateBackendDir()
	if err != nil {
		return nil, err
	}
	jarPath, err := locateBackendJar(backendDir)
	if err != nil {
		return nil, err
	}

	net, err := tcnetwork.New(ctx)
	if err != nil {
		return nil, fmt.Errorf("create docker network: %w", err)
	}

	// Infra first — the app's health check depends on Postgres + Redis being up.
	pg, err := postgres.Run(ctx, postgresImage,
		postgres.WithDatabase(dbName),
		postgres.WithUsername(dbUser),
		postgres.WithPassword(dbPassword),
		postgres.BasicWaitStrategies(),
		tcnetwork.WithNetwork([]string{"postgres"}, net),
	)
	if err != nil {
		return nil, fmt.Errorf("start postgres: %w", err)
	}

	rd, err := redis.Run(ctx, redisImage,
		tcnetwork.WithNetwork([]string{"redis"}, net),
	)
	if err != nil {
		return nil, fmt.Errorf("start redis: %w", err)
	}

	mailpit, err := startMailpit(ctx, net)
	if err != nil {
		return nil, fmt.Errorf("start mailpit: %w", err)
	}

	buildCtxDir, err := stageBuildContext(backendDir, jarPath)
	if err != nil {
		return nil, fmt.Errorf("stage backend build context: %w", err)
	}
	defer os.RemoveAll(buildCtxDir)

	app, err := startApp(ctx, net, buildCtxDir)
	if err != nil {
		return nil, fmt.Errorf("build/start backend app: %w", err)
	}

	host, err := app.Host(ctx)
	if err != nil {
		return nil, fmt.Errorf("app host: %w", err)
	}
	mappedPort, err := app.MappedPort(ctx, nat.Port(appPort))
	if err != nil {
		return nil, fmt.Errorf("app mapped port: %w", err)
	}

	stack := &Stack{
		network:  net,
		postgres: pg,
		redis:    rd,
		mailpit:  mailpit,
		app:      app,
		appHost:  host,
		appPort:  mappedPort.Int(),
	}

	// The actuator health check passing does NOT guarantee the platform-admin
	// bootstrap (a Spring ApplicationRunner) has finished — a real race found
	// while building this harness (see stage report). Spring Boot's
	// SpringApplication.run() logs "Started ... in X seconds" and fires
	// ApplicationListener.started() (after which Tomcat is already serving
	// requests) BEFORE calling callRunners() — so a request that lands in
	// that narrow window can 401 on the bootstrap admin's credentials even
	// though the container is "ready" by every wait-strategy definition.
	// Closing this here (once, in the harness) means every test using Get()
	// never has to defend against it individually.
	if err := waitForBootstrapAdmin(ctx, stack.AdminClient()); err != nil {
		return nil, fmt.Errorf("wait for bootstrap platform admin: %w", err)
	}

	return stack, nil
}

// waitForBootstrapAdmin bounded-polls the platform-admin token mint endpoint
// until the bootstrap credential works (see boot's doc comment for why this
// is necessary) or the timeout elapses. A genuinely wrong credential would
// also 401 forever, but the bootstrap credential is a package constant we
// control, so a persistent failure here means the race window never closed
// (or something is actually broken) — either way, timing out with the last
// error is the right failure mode, not retrying indefinitely.
func waitForBootstrapAdmin(ctx context.Context, admin *backend.AdminClient) error {
	deadline := time.Now().Add(30 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		if _, err := admin.MintPlatformAdminToken(ctx, BootstrapAdminEmail, BootstrapAdminPassword); err == nil {
			return nil
		} else {
			lastErr = err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(300 * time.Millisecond):
		}
	}
	return lastErr
}

func startMailpit(ctx context.Context, net *testcontainers.DockerNetwork) (testcontainers.Container, error) {
	req := testcontainers.ContainerRequest{
		Image:          mailpitImage,
		ExposedPorts:   []string{mailpitSMTPPort, mailpitHTTPPort},
		Networks:       []string{net.Name},
		NetworkAliases: map[string][]string{net.Name: {"mailpit"}},
		WaitingFor:     wait.ForListeningPort(mailpitHTTPPort),
	}
	return testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	})
}

// startApp builds the backend image from buildCtxDir (a staged, minimal
// Docker build context — see stageBuildContext for why it's staged rather
// than pointing FromDockerfile.Context directly at closeauth-backend/) and
// starts it wired to the sibling containers by their network aliases, on the
// `docker` Spring profile — config wiring verified against
// closeauth-integration-tests' CloseAuthStack.java (same env vars, same
// -D flags), still accurate for this stage.
//
// # Fixed (not random) host port for the app container — CLOSEAUTH_ISSUER_URL
//
// Cross-origin login continuity (CLOSEAUTH_CROSS_ORIGIN_LOGIN_DESIGN.md §3a):
// LoginSuccessResponder's post-login redirect is now built from
// properties.getIssuerUrl() + the authorization endpoint path — an ABSOLUTE,
// statically-configured URL, unlike the old SavedRequest.getRedirectUrl()
// (always implicitly relative to whatever host/port actually received the
// request). For that URL to be reachable by this Go process's own HTTP
// client (running on the host, outside the Docker network the app/Postgres/
// Redis/Mailpit containers share), issuer-url must be configured to equal
// this container's real host-reachable address — but Testcontainers only
// assigns the app's mapped host port AFTER the container starts, and
// CLOSEAUTH_ISSUER_URL must be set as an env var BEFORE it starts. So: grab a
// free host port ourselves first (reservePort, below) and explicitly bind
// the container's exposed port to it (HostConfigModifier), rather than
// leaving Docker to assign a random one — mirroring exactly what the
// backend's own CrossOriginLoginIntegrationTest.java does (a fixed port +
// closeauth.issuer-url dynamic property pointing at it), for the identical
// reason. Deliberately NOT a hardcoded constant (as that Java test uses,
// safely, since it's the only test process in its JVM): this harness is
// shared by two Go packages (internal/server, internal/backend) that `go
// test ./...` runs as separate, potentially-concurrent processes, and a
// shared hardcoded port would collide between them.
func startApp(ctx context.Context, net *testcontainers.DockerNetwork, buildCtxDir string) (testcontainers.Container, error) {
	hostPort, err := reservePort()
	if err != nil {
		return nil, fmt.Errorf("reserve a host port for the app container: %w", err)
	}
	issuerURL := fmt.Sprintf("http://localhost:%d%s", hostPort, appContextPath)

	javaOpts := strings.Join([]string{
		"-Dspring.profiles.active=docker",
		"-Dcloseauth.platform-admin.bootstrap-email=" + BootstrapAdminEmail,
		"-Dcloseauth.platform-admin.bootstrap-password=" + BootstrapAdminPassword,
		// The harness drives the app over plain HTTP; a Secure session cookie
		// would be withheld by the browser (or here, our own HTTP client)
		// over http, breaking the SSO flow. Production keeps the default
		// (secure=true) — this override is test/dev-only, same as the Java
		// module's documented posture.
		"-Dcloseauth.session.cookie.secure=false",
	}, " ")

	req := testcontainers.ContainerRequest{
		FromDockerfile: testcontainers.FromDockerfile{
			Context:       buildCtxDir,
			Dockerfile:    "Dockerfile",
			PrintBuildLog: true,
		},
		ExposedPorts:   []string{appPort},
		Networks:       []string{net.Name},
		NetworkAliases: map[string][]string{net.Name: {"closeauth-backend"}},
		HostConfigModifier: func(hc *container.HostConfig) {
			hc.PortBindings = nat.PortMap{
				nat.Port(appPort): []nat.PortBinding{{HostIP: "127.0.0.1", HostPort: strconv.Itoa(hostPort)}},
			}
		},
		Env: map[string]string{
			"SPRING_DATASOURCE_URL":      "jdbc:postgresql://postgres:5432/" + dbName,
			"SPRING_DATASOURCE_USERNAME": dbUser,
			"SPRING_DATASOURCE_PASSWORD": dbPassword,
			"SPRING_DATA_REDIS_HOST":     "redis",
			"SPRING_DATA_REDIS_PORT":     "6379",
			// Mail -> Mailpit; AUTH/STARTTLS off (Mailpit rejects both by default).
			"SMTP_HOST":     "mailpit",
			"SMTP_PORT":     strings.TrimSuffix(mailpitSMTPPort, "/tcp"),
			"SMTP_USERNAME": "",
			"SMTP_PASSWORD": "",
			"SMTP_AUTH":     "false",
			"SMTP_STARTTLS": "false",
			// Don't make the app's health depend on an SMTP handshake.
			"MANAGEMENT_HEALTH_MAIL_ENABLED": "false",
			// Cross-origin login continuity (see the fixed-port doc comment
			// above): must equal this container's actual host-reachable
			// address so LoginSuccessResponder's reconstructed
			// /oauth2/authorize redirect is genuinely followable by this
			// Go process's HTTP client.
			"CLOSEAUTH_ISSUER_URL": issuerURL,
			"JAVA_OPTS":            javaOpts,
		},
		WaitingFor: wait.ForHTTP(appContextPath + "/actuator/health").
			WithPort(nat.Port(appPort)).
			WithStatusCodeMatcher(func(status int) bool { return status == 200 }).
			WithStartupTimeout(4 * time.Minute),
		LogConsumerCfg: &testcontainers.LogConsumerConfig{
			Consumers: []testcontainers.LogConsumer{&testcontainers.StdoutLogConsumer{}},
		},
	}
	return testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	})
}

// reservePort asks the OS for a free TCP port on 127.0.0.1 and immediately
// releases it, so the caller can bind something else (here, the Docker
// container's host port mapping) to the SAME port number a moment later.
// This has an inherent, small TOCTOU race (another process could grab the
// port in between) — an accepted tradeoff for test harnesses needing to know
// a port number before the thing that will actually listen on it exists; see
// startApp's doc comment for why a fixed port is needed at all here.
func reservePort() (int, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

// ---- backend image inputs --------------------------------------------------

// locateBackendDir finds closeauth-backend/ as a sibling of closeauth-frontend/,
// resolved from this source file's own location (robust to whatever working
// directory `go test` happens to run from — always the package dir, but that
// varies with how the caller invokes it).
func locateBackendDir() (string, error) {
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		return "", fmt.Errorf("locate closeauth-backend: could not determine this source file's path")
	}
	// thisFile: .../closeauth-frontend/internal/testsupport/stack.go
	repoRoot := filepath.Join(filepath.Dir(thisFile), "..", "..", "..")
	dir := filepath.Clean(filepath.Join(repoRoot, "closeauth-backend"))
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return "", fmt.Errorf("locate closeauth-backend: %s not found (expected as a sibling of closeauth-frontend)", dir)
	}
	return dir, nil
}

// BackendJarPresent reports whether the backend boot jar has been packaged
// yet (the jar-based Dockerfile needs it). Exported so a caller can decide to
// skip-with-guidance BEFORE paying for anything else, exactly like the Java
// module's backendJarPresent().
func BackendJarPresent() bool {
	dir, err := locateBackendDir()
	if err != nil {
		return false
	}
	_, err = locateBackendJar(dir)
	return err == nil
}

func locateBackendJar(backendDir string) (string, error) {
	targetDir := filepath.Join(backendDir, "target")
	matches, err := filepath.Glob(filepath.Join(targetDir, "*.jar"))
	if err != nil {
		return "", fmt.Errorf("locate backend jar: %w", err)
	}
	for _, m := range matches {
		name := filepath.Base(m)
		if strings.HasSuffix(name, "-plain.jar") || strings.HasSuffix(name, "-sources.jar") || strings.HasSuffix(name, "-javadoc.jar") {
			continue
		}
		return m, nil
	}
	return "", fmt.Errorf(
		"backend boot jar not found in %s — the harness builds the app image from closeauth-backend/Dockerfile "+
			"(jar-based COPY), so the backend must be packaged first. From the repo root run:\n"+
			"    mvn -pl closeauth-backend -am package -DskipTests",
		targetDir)
}

// stageBuildContext copies Dockerfile + entrypoint.sh + the located jar into
// a fresh temp directory and uses THAT as the Docker build context, rather
// than pointing testcontainers-go's FromDockerfile.Context directly at
// closeauth-backend/.
//
// Why: closeauth-backend/.dockerignore excludes target/ wholesale (it's
// meant for the project's own `docker build` workflow, where the image and
// jar are built as separate, deliberate steps and target/ is usually stale
// build litter, not build input). testcontainers-go's context builder DOES
// read and honor a context directory's .dockerignore (confirmed in its
// source, container.go's parseDockerIgnore) — so pointing it straight at
// closeauth-backend/ would silently exclude target/*.jar from the upload,
// and the Dockerfile's `COPY target/*.jar /app.jar` would fail with "no
// matching files". This is the Go harness's analogue of the Docker-
// compatibility gotcha the stage prompt asked to watch for: not the Java
// module's docker-java API-version pin (that one didn't reproduce here — see
// the stage report), but a different environment-specific trap in the same
// spirit, found and worked around the same way the Java module worked around
// its own (there: stage files explicitly via ImageFromDockerfile.withFileFromPath;
// here: stage a directory with no .dockerignore in it at all).
func stageBuildContext(backendDir, jarPath string) (string, error) {
	tmp, err := os.MkdirTemp("", "closeauth-backend-image-*")
	if err != nil {
		return "", fmt.Errorf("create temp build context: %w", err)
	}

	if err := copyFile(filepath.Join(backendDir, "Dockerfile"), filepath.Join(tmp, "Dockerfile")); err != nil {
		os.RemoveAll(tmp)
		return "", err
	}
	if err := copyFile(filepath.Join(backendDir, "entrypoint.sh"), filepath.Join(tmp, "entrypoint.sh")); err != nil {
		os.RemoveAll(tmp)
		return "", err
	}
	if err := os.MkdirAll(filepath.Join(tmp, "target"), 0o755); err != nil {
		os.RemoveAll(tmp)
		return "", fmt.Errorf("create staged target dir: %w", err)
	}
	if err := copyFile(jarPath, filepath.Join(tmp, "target", filepath.Base(jarPath))); err != nil {
		os.RemoveAll(tmp)
		return "", err
	}
	return tmp, nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return fmt.Errorf("copy %s: %w", src, err)
	}
	defer in.Close()

	out, err := os.Create(dst)
	if err != nil {
		return fmt.Errorf("copy to %s: %w", dst, err)
	}
	defer out.Close()

	if _, err := io.Copy(out, in); err != nil {
		return fmt.Errorf("copy %s -> %s: %w", src, dst, err)
	}
	if info, statErr := os.Stat(src); statErr == nil {
		_ = os.Chmod(dst, info.Mode())
	}
	return nil
}
