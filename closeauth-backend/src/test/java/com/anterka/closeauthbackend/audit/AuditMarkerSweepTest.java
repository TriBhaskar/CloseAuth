package com.anterka.closeauthbackend.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage-8 coverage enforcement, Piece 1 — the marker sweep (the analogue of 7b's {@code GateEnumerationTest}). Every
 * stage since 3a left {@code TODO(stage-8)} markers at the security-relevant seams; wiring a seam means removing its
 * marker. This test scans the whole {@code src/main/java} tree and asserts ZERO markers remain — turning "a seam was
 * found but never actually wired" from a silent gap into a red build.
 *
 * <p>Runs by plain filesystem walk (no Spring context) so it ALWAYS runs in {@code mvn test}.
 */
class AuditMarkerSweepTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    // Built by concatenation so this test file's own source never contains the literal it hunts for.
    private static final String MARKER = "TODO" + "(stage-8)";

    @Test
    void noStageEightMarkersRemainInMainSources() {
        List<String> offenders = filesContaining(MAIN_SOURCES, MARKER);
        assertThat(offenders)
                .describedAs("Files still containing an unwired %s seam: %s", MARKER, offenders)
                .isEmpty();
    }

    /** Proves the sweep has teeth: a synthetic file containing the marker MUST be detected. */
    @Test
    void sweepDetectsAnIntroducedMarker() {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("audit-marker-teeth");
            Path planted = tempDir.resolve("Planted.java");
            Files.writeString(planted, "// " + MARKER + ": an artificially reintroduced gap\n");

            List<String> found = filesContaining(tempDir, MARKER);
            assertThat(found)
                    .describedAs("The sweep must flag an introduced %s marker (else it has no teeth)", MARKER)
                    .hasSize(1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private static List<String> filesContaining(Path root, String needle) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            if (Files.readString(p).contains(needle)) {
                                hits.add(p.toString());
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hits;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
