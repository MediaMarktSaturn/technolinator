package com.mediamarktsaturn.technolinator.sbom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the {@code technolinator-flutter} helper of the image against a fake Flutter SDK, whose Dart executable only records its call.
 */
class FlutterHelperTest {

    private static final Path HELPER = Path.of("src/main/docker/assets/technolinator-flutter").toAbsolutePath();

    @TempDir
    Path temp;

    Path sdk;
    Path repo;
    Path scratch;
    Path handover;
    Path androidHome;

    record Result(int exitCode, String output) {
    }

    @BeforeEach
    void setUp() throws IOException {
        sdk = Files.createDirectories(temp.resolve("sdks/flutter/3.44.1"));
        Files.createDirectories(sdk.resolve("bin/cache/dart-sdk/bin"));
        Files.createDirectories(sdk.resolve("bin/internal"));
        Files.createDirectories(sdk.resolve("packages/flutter/lib"));
        Files.createDirectories(sdk.resolve("packages/flutter_tools/.dart_tool"));
        Files.createDirectories(sdk.resolve("packages/flutter_tools/lib"));
        Files.createDirectories(sdk.resolve("packages/flutter_tools/gradle/src/main"));
        Files.createDirectories(sdk.resolve(".git"));
        Files.writeString(sdk.resolve("version"), "3.44.1\n");
        Files.writeString(sdk.resolve("bin/flutter"), "#!/bin/sh\necho original launcher >&2\nexit 99\n");
        Files.writeString(sdk.resolve("bin/dart"), "#!/bin/sh\necho original dart launcher >&2\nexit 99\n");
        Files.writeString(sdk.resolve("bin/cache/flutter_tools.snapshot"), "snapshot");
        Files.writeString(sdk.resolve("bin/cache/engine.stamp"), "stamp");
        Files.writeString(sdk.resolve("packages/flutter_tools/.dart_tool/package_config.json"), "{}");
        Files.writeString(sdk.resolve("packages/flutter_tools/gradle/build.gradle.kts"), "// build");
        Files.writeString(sdk.resolve("packages/flutter_tools/gradle/src/main/Plugin.kt"), "// plugin");
        var dart = Files.writeString(sdk.resolve("bin/cache/dart-sdk/bin/dart"), """
            #!/bin/sh
            printf '%s\\n' "$FLUTTER_ROOT" "$*" >> "$PWD/dart-called.txt"
            """);
        assertThat(dart.toFile().setExecutable(true)).isTrue();

        repo = Files.createDirectory(temp.resolve("repo"));
        scratch = Files.createDirectory(temp.resolve("scratch"));
        handover = Files.createFile(scratch.resolve("env"));
        androidHome = Files.createDirectory(temp.resolve("android-sdk"));
        Files.writeString(repo.resolve("pubspec.yaml"), "name: app\n");
    }

    @Test
    void setsUpOverlayAndRunsPubGetThroughTheToolSnapshot() throws Exception {
        Files.createDirectory(repo.resolve("nested"));
        Files.writeString(repo.resolve("nested/pubspec.yaml"), "name: nested\n");

        var result = run(repo, "3.44.1", ".", "nested");

        assertThat(result.exitCode()).as(result.output()).isZero();
        var overlay = scratch.resolve("flutter-3.44.1");
        var overlayReal = overlay.toString();
        for (var dir : List.of(repo, repo.resolve("nested"))) {
            var calls = Files.readAllLines(dir.resolve("dart-called.txt"));
            assertThat(calls.get(0)).isEqualTo(overlayReal);
            assertThat(calls.get(1)).startsWith("--packages=" + overlayReal + "/packages/flutter_tools/.dart_tool/package_config.json ")
                .contains(overlayReal + "/bin/cache/flutter_tools.snapshot")
                .endsWith("--suppress-analytics --no-version-check pub get");
        }
        assertThat(Files.readAllLines(handover)).contains("FLUTTER_ROOT=" + overlayReal, "FLUTTER_SDK=" + overlayReal,
            "PUB_CACHE=" + scratch.resolve("pub-cache"));
    }

    @Test
    void overlayLinksTheSdkExceptForTheFoldersFlutterWritesTo() throws Exception {
        run(repo, "3.44.1", ".");

        var overlay = scratch.resolve("flutter-3.44.1");
        // big and read-only parts are links to the installed SDK
        assertThat(Files.isSymbolicLink(overlay.resolve("bin/cache"))).isTrue();
        assertThat(Files.isSymbolicLink(overlay.resolve(".git"))).isTrue();
        assertThat(Files.isSymbolicLink(overlay.resolve("version"))).isTrue();
        assertThat(Files.isSymbolicLink(overlay.resolve("packages/flutter"))).isTrue();
        assertThat(Files.isSymbolicLink(overlay.resolve("packages/flutter_tools/lib"))).isTrue();
        assertThat(Files.isSymbolicLink(overlay.resolve("packages/flutter_tools/gradle/src"))).isTrue();
        assertThat(Files.isSymbolicLink(overlay.resolve("packages/flutter_tools/gradle/build.gradle.kts"))).isTrue();
        // the launcher is replaced, and the included Gradle build is writable
        assertThat(Files.isSymbolicLink(overlay.resolve("bin/flutter"))).isFalse();
        assertThat(overlay.resolve("bin/flutter")).isExecutable();
        assertThat(Files.isSymbolicLink(overlay.resolve("bin/dart"))).isFalse();
        assertThat(overlay.resolve("bin/dart")).isExecutable();
        assertThat(Files.isSymbolicLink(overlay.resolve("packages/flutter_tools/gradle"))).isFalse();
        Files.createDirectory(overlay.resolve("packages/flutter_tools/gradle/.gradle"));
        Files.writeString(overlay.resolve("packages/flutter_tools/gradle/build-output"), "x");
        assertThat(sdk.resolve("packages/flutter_tools/gradle/.gradle")).doesNotExist();
        assertThat(sdk.resolve("packages/flutter_tools/gradle/build-output")).doesNotExist();
        assertThat(Files.readString(sdk.resolve("bin/flutter"))).contains("original launcher");
    }

    @Test
    void dartOfTheOverlayRunsTheDartSdkDirectly() throws Exception {
        run(repo, "3.44.1", ".");
        Files.delete(repo.resolve("dart-called.txt"));

        var process = new ProcessBuilder(scratch.resolve("flutter-3.44.1/bin/dart").toString(), "run", "x").directory(repo.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(Files.readAllLines(repo.resolve("dart-called.txt")).get(1)).isEqualTo("run x");
    }

    @Test
    void completesAndroidLocalProperties() throws Exception {
        Files.createDirectory(repo.resolve("android"));

        run(repo, "3.44.1", ".");

        assertThat(repo.resolve("android/local.properties")).hasContent("sdk.dir=" + androidHome + "\n");
    }

    @Test
    void completesAndroidLocalPropertiesLackingTheLastLineBreak() throws Exception {
        Files.createDirectory(repo.resolve("android"));
        Files.writeString(repo.resolve("android/local.properties"), "flutter.sdk=/x");

        run(repo, "3.44.1", ".");

        assertThat(repo.resolve("android/local.properties")).hasContent("flutter.sdk=/x\nsdk.dir=" + androidHome + "\n");
    }

    @Test
    void keepsExistingAndroidSdkLocation() throws Exception {
        Files.createDirectory(repo.resolve("android"));
        Files.writeString(repo.resolve("android/local.properties"), "sdk.dir=/somewhere\n");

        run(repo, "3.44.1", ".");

        assertThat(repo.resolve("android/local.properties")).hasContent("sdk.dir=/somewhere\n");
    }

    @Test
    void setupOnlyPrintsExportsAndDoesNotRunFlutter() throws Exception {
        var result = run(repo, "--setup", "3.44.1");

        var overlay = scratch.resolve("flutter-3.44.1").toString();
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("export FLUTTER_ROOT=" + overlay).contains("export PATH=" + overlay + "/bin:");
        assertThat(repo.resolve("dart-called.txt")).doesNotExist();
        assertThat(Files.readAllLines(handover)).contains("FLUTTER_ROOT=" + overlay);
    }

    @Test
    void setupIsReusedByLaterCalls() throws Exception {
        run(repo, "--setup", "3.44.1");
        Files.writeString(scratch.resolve("flutter-3.44.1/marker"), "x");

        var result = run(repo, "3.44.1", ".");

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(scratch.resolve("flutter-3.44.1/marker")).exists();
    }

    @Test
    void rejectsVersionsWhichAreNotInstalled() throws Exception {
        var result = run(repo, "3.50.0", ".");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Flutter 3.50.0 is not installed").contains("3.44.1");
    }

    @Test
    void rejectsMalformedVersions() throws Exception {
        for (var version : List.of("../3.44.1", "stable", "3.44")) {
            assertThat(run(repo, version, ".").exitCode()).as(version).isNotZero();
        }
        assertThat(scratch.resolve("flutter-../3.44.1")).doesNotExist();
    }

    @Test
    void rejectsDirectoriesOutsideOfTheAnalysedFolder() throws Exception {
        Files.writeString(temp.resolve("pubspec.yaml"), "name: outside\n");
        Files.createSymbolicLink(repo.resolve("link"), temp);

        for (var dir : List.of("..", "link", temp.toString())) {
            var result = run(repo, "3.44.1", dir);
            assertThat(result.exitCode()).as(dir).isNotZero();
            assertThat(result.output()).as(dir).containsAnyOf("outside of the analysed folder", "relative to the analysed folder");
        }
        assertThat(temp.resolve("dart-called.txt")).doesNotExist();
    }

    @Test
    void rejectsDirectoriesWithoutPubspec() throws Exception {
        Files.createDirectory(repo.resolve("empty"));

        var result = run(repo, "3.44.1", "empty");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("no pubspec.yaml");
    }

    @Test
    void failsIfFlutterFails() throws Exception {
        Files.writeString(sdk.resolve("bin/cache/dart-sdk/bin/dart"), "#!/bin/sh\necho 'Because app depends on x, version solving failed.'\nexit 1\n");

        var result = run(repo, "3.44.1", ".");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("version solving failed");
    }

    @Test
    void refusesToRunOutsideOfAPrepareScript() throws Exception {
        var process = new ProcessBuilder("bash", HELPER.toString(), "3.44.1").directory(repo.toFile()).redirectErrorStream(true);
        process.environment().clear();
        process.environment().put("PATH", "/usr/bin:/bin");
        process.environment().put("SDK_DIR", temp.resolve("sdks").toString());
        var started = process.start();
        var output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(started.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(started.exitValue()).isNotZero();
        assertThat(output).contains("TECHNOLINATOR_SCRATCH");
    }

    private Result run(Path workingDir, String... args) throws Exception {
        var command = new java.util.ArrayList<String>(List.of("bash", HELPER.toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(workingDir.toFile()).redirectErrorStream(true);
        var env = builder.environment();
        env.clear();
        env.put("PATH", "/usr/bin:/bin");
        env.put("SDK_DIR", temp.resolve("sdks").toString());
        env.put("TECHNOLINATOR_SCRATCH", scratch.toString());
        env.put("TECHNOLINATOR_ENV", handover.toString());
        env.put("ANDROID_HOME", androidHome.toString());
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Result(process.exitValue(), output);
    }
}
