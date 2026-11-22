package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig.FlutterConfig;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import com.mediamarktsaturn.technolinator.os.Util;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class FlutterPreparation {

    private FlutterPreparation() {
    }

    static Uni<Map<String, String>> prepare(CdxgenClient.SbomCreationCommand cmd, Scratch scratch) {
        return prepare(cmd.repoDir(), cmd.flutter(), CdxgenClient.analysisEnvironment(cmd), cmd.projectName(), cmd.location(), scratch);
    }

    static Uni<Map<String, String>> prepare(Path root, FlutterConfig config, Map<String, String> environment,
                                             String projectName, String location, Scratch scratch) {
        if (config == null) {
            return Uni.createFrom().item(environment);
        }
        return Uni.createFrom().item(Unchecked.supplier(() -> setup(root, config, environment, scratch)))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .chain(setup -> {
                Uni<Void> commands = Uni.createFrom().voidItem();
                for (var directory : setup.directories()) {
                    commands = commands.chain(() -> ProcessHandler.runIsolated(
                            List.of(setup.sdk().resolve("bin/flutter").toString(), "--suppress-analytics", "pub", "get"),
                            directory, setup.environment())
                        .chain(result -> switch (result) {
                            case ProcessHandler.ProcessResult.Success ignored -> Uni.createFrom().voidItem();
                            case ProcessHandler.ProcessResult.Failure failure -> Uni.createFrom().failure(
                                failure.cause() instanceof ProcessFailedException pfe
                                    ? pfe.withProject(projectName, location) : failure.cause());
                        }));
                }
                return commands.replaceWith(setup.environment());
            });
    }

    private static Setup setup(Path root, FlutterConfig config, Map<String, String> environment, Scratch scratch) throws IOException {
        var version = config.version();
        if (version == null || !version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException("flutter.version must be an installed stable version, e.g. 3.44.1");
        }
        var sdkDir = environment.get("SDK_DIR");
        if (sdkDir == null || sdkDir.isBlank()) {
            throw new IllegalStateException("SDK_DIR must point to the installed Flutter SDKs");
        }
        var source = Path.of(sdkDir).resolve("flutter").resolve(version).toAbsolutePath().normalize();
        if (!Files.isExecutable(source.resolve("bin/flutter"))) {
            throw new IllegalStateException("Flutter version " + version + " is not installed at " + source);
        }
        source = source.toRealPath();
        var temporaryDirectory = Files.createTempDirectory("technolinator-flutter-");
        scratch.register(temporaryDirectory);
        var sdk = temporaryDirectory.resolve("sdk");
        try {
            copySdk(source, sdk, scratch);
        } finally {
            scratch.copyComplete();
        }
        var env = new HashMap<>(environment);
        env.put("FLUTTER_ROOT", sdk.toString());
        env.put("FLUTTER_SDK", sdk.toString());
        env.put("PATH", sdk.resolve("bin") + System.getProperty("path.separator") + environment.getOrDefault("PATH", ""));
        var androidSdk = env.get("ANDROID_HOME");
        if (androidSdk == null || androidSdk.isBlank()) {
            androidSdk = env.get("ANDROID_SDK_ROOT");
        }
        if (androidSdk != null && !androidSdk.isBlank()) {
            if (!Files.isDirectory(Path.of(androidSdk))) {
                throw new IllegalStateException("Android SDK directory does not exist: " + androidSdk);
            }
            env.put("ANDROID_HOME", androidSdk);
            env.put("ANDROID_SDK_ROOT", androidSdk);
        }

        var realRoot = root.toRealPath();
        var configuredDirectories = config.directories() == null ? List.of(".") : config.directories();
        if (configuredDirectories.isEmpty()) {
            throw new IllegalArgumentException("flutter.directories must not be empty");
        }
        var directories = new ArrayList<Path>();
        for (var configured : configuredDirectories) {
            if (configured == null || configured.isBlank() || Path.of(configured).isAbsolute()) {
                throw new IllegalArgumentException("Flutter directories must be relative to the analysis location");
            }
            var directory = realRoot.resolve(configured).normalize().toRealPath();
            if (!directory.startsWith(realRoot) || !Files.isRegularFile(directory.resolve("pubspec.yaml"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Flutter directory must be inside the analysis location and contain pubspec.yaml: " + configured);
            }
            var android = directory.resolve("android");
            if (Files.exists(android, LinkOption.NOFOLLOW_LINKS)) {
                if (androidSdk == null || androidSdk.isBlank()) {
                    throw new IllegalStateException("ANDROID_HOME or ANDROID_SDK_ROOT must point to an installed Android SDK");
                }
                var realAndroid = android.toRealPath();
                if (!realAndroid.startsWith(realRoot) || !Files.isDirectory(realAndroid)) {
                    throw new IllegalArgumentException("Android directory must be inside the analysis location: " + configured);
                }
                scratch.configureAndroid(realAndroid.resolve("local.properties"), sdk, androidSdk);
            }
            directories.add(directory);
        }
        return new Setup(env, List.copyOf(directories), sdk);
    }

    private static void copySdk(Path source, Path target, Scratch scratch) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                scratch.checkOpen();
                var destination = target.resolve(source.relativize(directory));
                Files.copy(directory, destination, StandardCopyOption.COPY_ATTRIBUTES);
                makeWritable(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                scratch.checkOpen();
                if (attrs.isSymbolicLink()) {
                    var link = Files.readSymbolicLink(file);
                    if (link.isAbsolute() || !file.getParent().resolve(link).normalize().startsWith(source)) {
                        throw new IOException("Flutter SDK contains a link outside the SDK: " + file);
                    }
                }
                var destination = target.resolve(source.relativize(file));
                Files.copy(file, destination, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                if (!attrs.isSymbolicLink()) {
                    makeWritable(destination);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void makeWritable(Path path) throws IOException {
        var permissions = Files.getPosixFilePermissions(path);
        permissions.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(path, permissions);
    }

    private static void configureAndroid(Path file, Path flutterSdk, String androidSdk) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("android/local.properties must not be a symbolic link");
        }
        var properties = new Properties();
        if (Files.exists(file)) {
            try (var input = Files.newInputStream(file)) {
                properties.load(input);
            }
        }
        properties.setProperty("flutter.sdk", flutterSdk.toString());
        if (androidSdk != null && !androidSdk.isBlank()) {
            properties.setProperty("sdk.dir", androidSdk);
        }
        try (var output = Files.newOutputStream(file)) {
            properties.store(output, "Configured by Technolinator");
        }
    }

    private record Setup(Map<String, String> environment, List<Path> directories, Path sdk) {
    }

    static final class Scratch {
        private Path directory;
        private volatile boolean closed;
        private boolean copying;
        private final Map<Path, byte[]> originalProperties = new HashMap<>();

        synchronized void register(Path path) {
            if (closed) {
                Util.removeAsync(path);
                throw new IllegalStateException("Flutter preparation was cancelled");
            }
            directory = path;
            copying = true;
        }

        void checkOpen() throws IOException {
            if (closed) {
                throw new IOException("Flutter preparation was cancelled");
            }
        }

        synchronized void copyComplete() {
            copying = false;
            if (closed && directory != null) {
                Util.removeAsync(directory);
                directory = null;
            }
        }

        synchronized void configureAndroid(Path file, Path flutterSdk, String androidSdk) throws IOException {
            checkOpen();
            if (Files.isSymbolicLink(file)) {
                throw new IllegalArgumentException("android/local.properties must not be a symbolic link");
            }
            if (!originalProperties.containsKey(file)) {
                originalProperties.put(file, Files.exists(file) ? Files.readAllBytes(file) : null);
            }
            FlutterPreparation.configureAndroid(file, flutterSdk, androidSdk);
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                for (var entry : originalProperties.entrySet()) {
                    try {
                        if (entry.getValue() == null) {
                            Files.deleteIfExists(entry.getKey());
                        } else {
                            if (Files.isSymbolicLink(entry.getKey())) {
                                throw new IOException("android/local.properties became a symbolic link: " + entry.getKey());
                            }
                            Files.write(entry.getKey(), entry.getValue());
                        }
                    } catch (IOException e) {
                        if (failure == null) {
                            failure = e;
                        } else {
                            failure.addSuppressed(e);
                        }
                    }
                }
            } finally {
                if (!copying && directory != null) {
                    Util.removeAsync(directory);
                    directory = null;
                }
                originalProperties.clear();
            }
            if (failure != null) {
                throw new UncheckedIOException("Failed to restore Android local.properties", failure);
            }
        }
    }
}
