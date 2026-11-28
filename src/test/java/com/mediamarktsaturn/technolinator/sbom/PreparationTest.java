package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig.PrepareConfig;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class PreparationTest {

    @TempDir
    Path temp;

    Path root;
    Map<String, String> cdxgenEnv = Map.of("PATH", "/usr/bin:/bin", "FOR_CDXGEN", "yes");
    Map<String, String> scriptEnv = Map.of("PATH", "/usr/bin:/bin", "REPO_CONFIGURED", "kept");
    Preparation.Scratch scratch = new Preparation.Scratch();

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(temp.resolve("repo"));
    }

    @AfterEach
    void cleanUp() {
        scratch.close();
    }

    @Test
    void runsTheScriptInTheAnalysedFolderWithPrivateDirectories() throws IOException {
        run("""
            pwd > where.txt
            printf '%s\\n' "$HOME" "$TMPDIR" "$PUB_CACHE" "$TECHNOLINATOR_SCRATCH" "$REPO_CONFIGURED" > env.txt
            """);

        assertThat(Files.readString(root.resolve("where.txt")).strip()).isEqualTo(root.toRealPath().toString());
        var lines = Files.readAllLines(root.resolve("env.txt"));
        var scratchDir = lines.get(3);
        assertThat(scratchDir).startsWith(System.getProperty("java.io.tmpdir")).doesNotStartWith(root.toString());
        assertThat(lines.get(0)).isEqualTo(scratchDir + "/home");
        assertThat(lines.get(1)).isEqualTo(scratchDir + "/tmp");
        assertThat(lines.get(2)).isEqualTo(scratchDir + "/pub-cache");
        assertThat(lines.get(4)).isEqualTo("kept");
    }

    @Test
    void handsOverVariablesToCdxgen() {
        var env = run("""
            echo '# a comment' >> "$TECHNOLINATOR_ENV"
            echo 'FLUTTER_ROOT=/some/where' >> "$TECHNOLINATOR_ENV"
            echo '' >> "$TECHNOLINATOR_ENV"
            echo 'FOR_CDXGEN=overridden=with=equals' >> "$TECHNOLINATOR_ENV"
            echo 'FLUTTER_ROOT=/later/one' >> "$TECHNOLINATOR_ENV"
            """);

        assertThat(env).containsEntry("FLUTTER_ROOT", "/later/one")
            .containsEntry("FOR_CDXGEN", "overridden=with=equals")
            .containsEntry("PATH", "/usr/bin:/bin");
    }

    @Test
    void rejectsMalformedHandover() {
        assertRejected("echo 'not valid' >> \"$TECHNOLINATOR_ENV\"", "NAME=value");
        assertRejected("echo '1ABC=x' >> \"$TECHNOLINATOR_ENV\"", "NAME=value");
    }

    @Test
    void rejectsReplacedHandoverFile() {
        assertRejected("rm \"$TECHNOLINATOR_ENV\" && ln -s /etc/passwd \"$TECHNOLINATOR_ENV\"", "regular file");
    }

    @Test
    void failureOfTheScriptIsReportedAsPrepareFailure() {
        assertThatThrownBy(() -> prepare("echo dependency-error; exit 2", "sub"))
            .hasCauseInstanceOf(ProcessFailedException.class)
            .cause().isInstanceOfSatisfying(ProcessFailedException.class, failure -> {
                assertThat(failure.command()).isEqualTo(CdxgenClient.PREPARE_COMMAND);
                assertThat(failure.exitCode()).isEqualTo(2);
                assertThat(failure.outputLines()).contains("dependency-error");
                assertThat(failure.projectName()).isEqualTo("example");
                assertThat(failure.location()).isEqualTo("sub");
            });
    }

    @Test
    void stopsAtTheFirstFailingCommand() {
        assertThatThrownBy(() -> prepare("false; touch not-reached", ""))
            .hasCauseInstanceOf(ProcessFailedException.class);
        assertThat(root.resolve("not-reached")).doesNotExist();
    }

    @Test
    void removesSbomCreatedByTheScript() throws IOException {
        run("echo '{}' > sbom.json");

        assertThat(root.resolve("sbom.json")).doesNotExist();
    }

    @Test
    void removesSbomSymlinkWithoutTouchingItsTarget() throws IOException {
        var outside = Files.writeString(temp.resolve("outside.json"), "keep");
        Files.createSymbolicLink(root.resolve("sbom.json"), outside);

        run("true");

        assertThat(root.resolve("sbom.json")).doesNotExist();
        assertThat(outside).hasContent("keep");
    }

    @Test
    void rejectsSymbolicLinksLeavingTheRepository() {
        assertRejected("ln -s /proc/self/environ leak", "outside of the repository");
        assertRejected("ln -s ../../../../etc/passwd leak", "outside of the repository");
        assertRejected("mkdir sub && ln -s ../../ sub/up", "outside of the repository");
    }

    @Test
    void acceptsSymbolicLinksInsideTheRepositoryAndTheScratchDirectory() throws IOException {
        run("""
            mkdir -p sub
            echo hi > sub/file
            ln -s sub/file link-in-repo
            ln -s "$TECHNOLINATOR_SCRATCH/home" link-in-scratch
            """);

        assertThat(Files.isSymbolicLink(root.resolve("link-in-repo"))).isTrue();
        assertThat(Files.isSymbolicLink(root.resolve("link-in-scratch"))).isTrue();
        // the scratch directory is removed in the background, don't let the cleanup of the test dir race with that
        Files.delete(root.resolve("link-in-scratch"));
    }

    @Test
    void backgroundProcessesNeitherBlockNorSurviveThePreparation() throws IOException {
        var started = System.nanoTime();

        run("""
            sleep 300 &
            echo $! > background.pid
            """);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        var pid = Long.parseLong(Files.readString(root.resolve("background.pid")).strip());
        await().atMost(Duration.ofSeconds(10)).until(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
    }

    @Test
    void backgroundProcessesOfCancelledPreparationsAreKilledOnClose() throws IOException {
        var isolated = new Preparation.Scratch();
        // the subshell detaches the sleep from the script, so it's no descendant of it
        var running = Preparation.prepare(root, new PrepareConfig("""
                (sleep 300 & echo $! > background.pid)
                sleep 300
                """), cdxgenEnv, scriptEnv, "example", "", isolated)
            .subscribe().with(env -> {
            }, failure -> {
            });
        var pidFile = root.resolve("background.pid");
        await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(pidFile) && !Files.readString(pidFile).isBlank());
        var pid = Long.parseLong(Files.readString(pidFile).strip());

        running.cancel();
        isolated.close();

        await().atMost(Duration.ofSeconds(10)).until(() -> ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
    }

    @Test
    void rejectsUnreadableLeftovers() {
        assertRejected("mkdir locked && chmod 000 locked", "can't be read");
    }

    @Test
    void rejectsReservedNamesInHandover() {
        assertRejected("echo 'TECHNOLINATOR_SCRATCH=/' >> \"$TECHNOLINATOR_ENV\"", "reserved");
    }

    @Test
    void checksTheWholeCheckoutNotJustTheAnalysedFolder() throws IOException {
        var analysed = Files.createDirectories(root.resolve("projects/app"));
        var isolated = new Preparation.Scratch();
        try {
            assertThatThrownBy(() -> Preparation.prepare(analysed, new PrepareConfig("ln -s /etc ../../planted"), cdxgenEnv, scriptEnv,
                "example", "projects/app", isolated).await().atMost(Duration.ofSeconds(20)))
                .hasCauseInstanceOf(ProcessFailedException.class)
                .cause().isInstanceOfSatisfying(ProcessFailedException.class, failure ->
                    assertThat(String.join("\n", failure.outputLines())).contains("planted"));
        } finally {
            isolated.close();
        }
    }

    @Test
    void checkoutRootIsDerivedFromTheLocation() {
        assertThat(Preparation.checkoutRoot(Path.of("/tmp/repo/a/b"), "a/b")).isEqualTo(Path.of("/tmp/repo"));
        assertThat(Preparation.checkoutRoot(Path.of("/tmp/repo"), "")).isEqualTo(Path.of("/tmp/repo"));
        assertThat(Preparation.checkoutRoot(Path.of("/tmp/repo/a"), null)).isEqualTo(Path.of("/tmp/repo/a"));
    }

    @Test
    void scratchDirectoryLivesUntilItIsClosed() throws IOException {
        run("echo \"$TECHNOLINATOR_SCRATCH\" > scratch.txt");
        var scratchDir = Path.of(Files.readString(root.resolve("scratch.txt")).strip());
        assertThat(scratchDir).isDirectory();

        scratch.close();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(scratchDir).doesNotExist());
    }

    @Test
    void closedScratchRefusesNewPreparations() {
        scratch.close();

        assertThatThrownBy(() -> prepare("true", ""))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cancelled");
    }

    @Test
    void preparationEnvironmentKeepsConfiguredValuesButNotTechnolinatorSecrets() {
        var parent = Map.of(
            "PATH", "/usr/bin",
            "HOME", "/data",
            "QUARKUS_GITHUB_APP_PRIVATE_KEY", "pem",
            "DTRACK_APIKEY", "key",
            "CDXGEN_SOMETHING_TOKEN", "t",
            "MY_INHERITED", "i",
            "ACCESSIBLE_BY_NAME", "n");
        var explicit = Map.of(
            "GITHUB_TOKEN", "gh",
            "ARTIFACTORY_TOKEN", "from-repo-config",
            "MVN_ARGS", "-B");

        var env = CdxgenClient.preparationEnvironment(parent, List.of("MY_*", "ACCESSIBLE_BY_NAME"), List.of("MY_INHERITED"), explicit);

        assertThat(env).containsOnlyKeys("PATH", "HOME", "ACCESSIBLE_BY_NAME", "ARTIFACTORY_TOKEN", "MVN_ARGS");
    }

    private Map<String, String> run(String script) {
        return Preparation.prepare(root, new PrepareConfig(script), cdxgenEnv, scriptEnv, "example", "", scratch)
            .await().atMost(Duration.ofSeconds(20));
    }

    private Map<String, String> prepare(String script, String location) {
        return Preparation.prepare(root, new PrepareConfig(script), cdxgenEnv, scriptEnv, "example", location, scratch)
            .await().atMost(Duration.ofSeconds(20));
    }

    private void assertRejected(String script, String reason) {
        var isolated = new Preparation.Scratch();
        try {
            assertThatThrownBy(() -> Preparation.prepare(root, new PrepareConfig(script), cdxgenEnv, scriptEnv, "example", "", isolated)
                .await().atMost(Duration.ofSeconds(20)))
                .hasCauseInstanceOf(ProcessFailedException.class)
                .cause().isInstanceOfSatisfying(ProcessFailedException.class, failure -> {
                    assertThat(failure.command()).isEqualTo(CdxgenClient.PREPARE_COMMAND);
                    assertThat(String.join("\n", failure.outputLines())).contains(reason);
                });
        } finally {
            isolated.close();
            deleteLeftovers();
        }
    }

    private void deleteLeftovers() {
        try (var files = Files.list(root)) {
            files.forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // directories are handled by the temp dir cleanup
                }
            });
        } catch (IOException e) {
            // nothing to do
        }
    }
}
