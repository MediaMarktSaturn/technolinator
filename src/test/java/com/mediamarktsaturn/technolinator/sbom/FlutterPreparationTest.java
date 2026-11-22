package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig.FlutterConfig;
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
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class FlutterPreparationTest {
    @TempDir
    Path temp;

    Path root;
    Path sdk;
    Map<String, String> env;
    FlutterPreparation.Scratch scratch = new FlutterPreparation.Scratch();

    @AfterEach
    void cleanUp() {
        scratch.close();
    }

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(temp.resolve("repo"));
        Files.writeString(root.resolve("pubspec.yaml"), "name: example\n");
        Files.createDirectory(root.resolve("android"));
        sdk = Files.createDirectories(temp.resolve("sdks/flutter/3.44.1"));
        Files.createDirectory(sdk.resolve("bin"));
        Files.createDirectory(temp.resolve("android-sdk"));
        script("printf '%s\\n' \"$*\" \"$FLUTTER_ROOT\" > prepared.txt\n");
        env = Map.of("SDK_DIR", temp.resolve("sdks").toString(), "PATH", "/usr/bin:/bin",
            "ANDROID_HOME", temp.resolve("android-sdk").toString());
    }

    @Test
    void preparesFlutterAndPreservesAndroidProperties() throws IOException {
        var original = "other.setting=keep\nflutter.sdk=old\n";
        Files.writeString(root.resolve("android/local.properties"), original);
        var result = FlutterPreparation.prepare(root, new FlutterConfig("3.44.1", null), env, "example", "", scratch)
            .await().atMost(Duration.ofSeconds(10));

        var stagedSdk = result.get("FLUTTER_ROOT");
        assertThat(stagedSdk).isNotEqualTo(sdk.toString());
        assertThat(Files.readAllLines(root.resolve("prepared.txt")))
            .containsExactly("--suppress-analytics pub get", stagedSdk);
        var properties = new Properties();
        try (var input = Files.newInputStream(root.resolve("android/local.properties"))) {
            properties.load(input);
        }
        assertThat(properties.getProperty("other.setting")).isEqualTo("keep");
        assertThat(properties.getProperty("flutter.sdk")).isEqualTo(stagedSdk);
        assertThat(properties.getProperty("sdk.dir")).isEqualTo(env.get("ANDROID_HOME"));
        assertThat(result).containsEntry("FLUTTER_SDK", stagedSdk);
        scratch.close();
        assertThat(root.resolve("android/local.properties")).hasContent(original);
    }

    @Test
    void runsEveryConfiguredDirectory() throws IOException {
        var nested = Files.createDirectories(root.resolve("nested"));
        Files.writeString(nested.resolve("pubspec.yaml"), "name: nested\n");
        FlutterPreparation.prepare(root, new FlutterConfig("3.44.1", List.of(".", "nested")), env, "example", "", scratch)
            .await().atMost(Duration.ofSeconds(10));
        assertThat(root.resolve("prepared.txt")).exists();
        assertThat(nested.resolve("prepared.txt")).exists();
        scratch.close();
        assertThat(root.resolve("android/local.properties")).doesNotExist();
    }

    @Test
    void propagatesPubFailure() throws IOException {
        script("echo dependency-error; exit 2\n");
        assertThatThrownBy(() -> FlutterPreparation.prepare(root, new FlutterConfig("3.44.1", null), env, "example", "sub", scratch)
            .await().atMost(Duration.ofSeconds(10)))
            .hasCauseInstanceOf(ProcessFailedException.class)
            .cause().isInstanceOfSatisfying(ProcessFailedException.class, failure -> {
                assertThat(failure.exitCode()).isEqualTo(2);
                assertThat(failure.outputLines()).contains("dependency-error");
                assertThat(failure.projectName()).isEqualTo("example");
                assertThat(failure.location()).isEqualTo("sub");
            });
        scratch.close();
        assertThat(root.resolve("android/local.properties")).doesNotExist();
    }

    @Test
    void rejectsUninstalledAndInvalidVersions() {
        assertThatThrownBy(() -> FlutterPreparation.prepare(root, new FlutterConfig("../outside", null), env, "example", "", scratch)
            .await().atMost(Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FlutterPreparation.prepare(root, new FlutterConfig("9.9.9", null), env, "example", "", scratch)
            .await().atMost(Duration.ofSeconds(10))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsDirectoriesOutsideAnalysisLocation() throws IOException {
        Files.createSymbolicLink(root.resolve("outside"), temp);
        assertThatThrownBy(() -> FlutterPreparation.prepare(root, new FlutterConfig("3.44.1", List.of("outside")), env, "example", "", scratch)
            .await().atMost(Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSymlinkedLocalProperties() throws IOException {
        var outside = Files.writeString(temp.resolve("outside.properties"), "keep=true\n");
        Files.createSymbolicLink(root.resolve("android/local.properties"), outside);
        assertThatThrownBy(() -> FlutterPreparation.prepare(root, new FlutterConfig("3.44.1", null), env, "example", "", scratch)
            .await().atMost(Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
        assertThat(outside).hasContent("keep=true\n");
    }

    @Test
    void requiresAnAndroidSdkWhenAnAndroidProjectExists() {
        var noAndroid = Map.of("SDK_DIR", env.get("SDK_DIR"), "PATH", env.get("PATH"));
        assertThatThrownBy(() -> FlutterPreparation.prepare(root, new FlutterConfig("3.44.1", null),
            noAndroid, "example", "", scratch).await().atMost(Duration.ofSeconds(10)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ANDROID_HOME");
    }

    @Test
    void usesAndroidSdkRootWhenAndroidHomeIsBlank() {
        var androidRoot = Map.of("SDK_DIR", env.get("SDK_DIR"), "PATH", env.get("PATH"),
            "ANDROID_HOME", "", "ANDROID_SDK_ROOT", env.get("ANDROID_HOME"));
        var result = FlutterPreparation.prepare(root, new FlutterConfig("3.44.1", null),
            androidRoot, "example", "", scratch).await().atMost(Duration.ofSeconds(10));
        assertThat(result).containsEntry("ANDROID_HOME", env.get("ANDROID_HOME"));
    }

    @Test
    void waitsForCancelledSdkCopyBeforeCleaningUp() throws IOException {
        var staging = Files.createDirectory(temp.resolve("staging"));
        scratch.register(staging);
        scratch.close();
        assertThat(staging).exists();
        scratch.copyComplete();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(staging).doesNotExist());
    }

    private void script(String content) throws IOException {
        var executable = sdk.resolve("bin/flutter");
        Files.writeString(executable, "#!/bin/sh\n" + content);
        if (!executable.toFile().setExecutable(true)) {
            throw new IOException("Cannot make fake Flutter executable");
        }
    }
}
