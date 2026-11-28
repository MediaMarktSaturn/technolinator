package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig.PrepareConfig;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import com.mediamarktsaturn.technolinator.os.Util;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs the {@code prepare} script of a repository configuration ahead of cdxgen.
 * <p>
 * The script is run by bash in the analysed folder with a private scratch directory ({@code $TECHNOLINATOR_SCRATCH}),
 * and without the secrets of Technolinator. Variables for cdxgen can be handed over by writing {@code NAME=value} lines to
 * {@code $TECHNOLINATOR_ENV}.
 * <p>
 * Note: the script is <b>not sandboxed</b>, it has the permissions of Technolinator itself.
 */
final class Preparation {

    static final String ENV_SCRATCH = "TECHNOLINATOR_SCRATCH";
    static final String ENV_HANDOVER = "TECHNOLINATOR_ENV";

    /**
     * Upper bound of the hand-over file, it's meant for a few paths and flags.
     */
    static final long MAX_HANDOVER_BYTES = 64 * 1024;

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Preparation() {
    }

    static Uni<Map<String, String>> prepare(CdxgenClient.SbomCreationCommand cmd, Scratch scratch) {
        if (cmd.prepare() == null) {
            return Uni.createFrom().item(CdxgenClient.analysisEnvironment(cmd));
        }
        return prepare(cmd.repoDir(), cmd.prepare(), CdxgenClient.analysisEnvironment(cmd), CdxgenClient.preparationEnvironment(cmd),
            cmd.projectName(), cmd.location(), scratch);
    }

    /**
     * @param root           the folder to analyse, the working directory of the script
     * @param cdxgenEnv      the environment of cdxgen, the variables handed over by the script are added to it
     * @param scriptBaseEnv the environment of the script, as it is before the variables of the scratch directory are added
     * @return the environment for cdxgen
     */
    static Uni<Map<String, String>> prepare(Path root, PrepareConfig config, Map<String, String> cdxgenEnv, Map<String, String> scriptBaseEnv,
                                             String projectName, String location, Scratch scratch) {
        return Uni.createFrom().item(Unchecked.supplier(() -> setup(config, scriptBaseEnv, scratch)))
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .chain(setup -> ProcessHandler.runIsolated(List.of("bash", "-euo", "pipefail", setup.script().toString()), root, setup.environment(),
                    // processes left running in the background would keep the output open, and could change the checkout later on
                    () -> killLeftovers(setup.scratch()))
                .chain(result -> switch (result) {
                    case ProcessHandler.ProcessResult.Success ignored -> Uni.createFrom().item(Unchecked.supplier(
                            () -> finish(root, location, setup, cdxgenEnv, projectName)))
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
                    case ProcessHandler.ProcessResult.Failure failure -> Uni.createFrom().failure(
                        failure.cause() instanceof ProcessFailedException pfe
                            ? pfe.withCommand(CdxgenClient.PREPARE_COMMAND).withProject(projectName, location) : failure.cause());
                }));
    }

    private static Setup setup(PrepareConfig config, Map<String, String> baseEnv, Scratch scratch) throws IOException {
        var dir = scratch.create();
        var home = Files.createDirectory(dir.resolve("home"));
        var tmp = Files.createDirectory(dir.resolve("tmp"));
        var handover = Files.createFile(dir.resolve("env"));
        var script = Files.writeString(dir.resolve("prepare.sh"), config.script());

        var env = new HashMap<>(baseEnv);
        env.put("HOME", home.toString());
        env.put("TMPDIR", tmp.toString());
        env.put("TMP", tmp.toString());
        env.put("TEMP", tmp.toString());
        env.put("XDG_CACHE_HOME", home.resolve(".cache").toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("XDG_DATA_HOME", home.resolve(".local/share").toString());
        env.put("PUB_CACHE", dir.resolve("pub-cache").toString());
        env.put(ENV_SCRATCH, dir.toString());
        env.put(ENV_HANDOVER, handover.toString());
        return new Setup(Map.copyOf(env), script, handover, dir);
    }

    /**
     * Makes sure the script left nothing behind that could mislead or harm cdxgen, and collects what it wants to hand over.
     *
     * @param location the analysed folder relative to the root of the checkout, the script might have changed the checkout as a whole
     */
    private static Map<String, String> finish(Path root, String location, Setup setup, Map<String, String> cdxgenEnv, String projectName)
        throws IOException, ProcessFailedException {
        killLeftovers(setup.scratch());
        try {
            // cdxgen writes its result there, so it must neither be prepared nor point somewhere else
            Util.removeRecursively(root.resolve(CdxgenClient.SBOM_JSON));
            rejectForeignLinks(checkoutRoot(root, location), setup.scratch());
            var env = new HashMap<>(cdxgenEnv);
            env.putAll(readHandover(setup.handover()));
            return env;
        } catch (Rejected rejected) {
            throw new ProcessFailedException(CdxgenClient.PREPARE_COMMAND, null, List.of("ERROR: " + rejected.getMessage()), projectName, location);
        }
    }

    /**
     * @return the root of the checkout the analysed folder [root] is located in at [location]
     */
    static Path checkoutRoot(Path root, String location) {
        var checkout = root.toAbsolutePath().normalize();
        if (location != null && !location.isBlank()) {
            for (var i = 0; i < Path.of(location).normalize().getNameCount() && checkout.getParent() != null; i++) {
                checkout = checkout.getParent();
            }
        }
        return checkout;
    }

    /**
     * Kills the processes started by the script that are still running (daemons of build tools, anything started in the background).
     * They are recognized by the scratch directory in their environment, as they aren't descendants of Technolinator anymore once the
     * script exited. Best effort: a process that cleared its environment isn't found.
     */
    static void killLeftovers(Path scratch) {
        var marker = (ENV_SCRATCH + "=" + scratch).getBytes(StandardCharsets.UTF_8);
        var self = ProcessHandle.current().pid();
        ProcessHandle.allProcesses()
            .filter(process -> process.pid() != self)
            .filter(process -> hasEnvEntry(process.pid(), marker))
            .forEach(process -> {
                Log.warnf("Killing process %s left behind by a prepare script: %s", process.pid(), process.info().commandLine().orElse(""));
                process.destroyForcibly();
            });
    }

    private static boolean hasEnvEntry(long pid, byte[] entry) {
        byte[] environ;
        try {
            environ = Files.readAllBytes(Path.of("/proc", Long.toString(pid), "environ"));
        } catch (IOException | RuntimeException e) {
            // gone already, of another user, or no /proc at all
            return false;
        }
        var start = 0;
        for (var i = 0; i <= environ.length; i++) {
            if (i == environ.length || environ[i] == 0) {
                if (i - start == entry.length && Arrays.equals(environ, start, i, entry, 0, entry.length)) {
                    return true;
                }
                start = i + 1;
            }
        }
        return false;
    }

    /**
     * Symbolic links are fine as long as they stay within the repository or the scratch directory.
     * Anything else would let cdxgen (and the build tools it starts) read or write files of Technolinator.
     */
    static void rejectForeignLinks(Path root, Path scratch) throws IOException, Rejected {
        var realRoot = root.toRealPath();
        var realScratch = scratch.toRealPath();
        try {
            Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) {
                        var link = Files.readSymbolicLink(file);
                        var target = file.getParent().resolve(link).normalize();
                        var effective = Files.exists(file) ? file.toRealPath() : target;
                        if (!isWithin(effective, realRoot, realScratch, scratch.toAbsolutePath().normalize())) {
                            throw new Foreign("a symbolic link points outside of the repository: " + realRoot.relativize(file) + " -> " + link);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    if (exc instanceof AccessDeniedException) {
                        throw new Foreign("it can't be read: " + realRoot.relativize(file));
                    }
                    throw exc;
                }
            });
        } catch (Foreign foreign) {
            throw new Rejected("The prepare script left the checkout in a state that can't be analysed safely, " + foreign.getMessage());
        }
    }

    private static boolean isWithin(Path path, Path... parents) {
        for (var parent : parents) {
            if (path.startsWith(parent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the {@code NAME=value} lines the script wrote to {@code $TECHNOLINATOR_ENV}; empty lines and lines starting with # are ignored,
     * a later assignment wins.
     */
    static Map<String, String> readHandover(Path file) throws IOException, Rejected {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new Rejected("The prepare script replaced the file $" + ENV_HANDOVER + " (it must be a regular file)");
        }
        if (Files.size(file) > MAX_HANDOVER_BYTES) {
            throw new Rejected("The file $" + ENV_HANDOVER + " is larger than " + MAX_HANDOVER_BYTES + " bytes");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            throw new Rejected("The file $" + ENV_HANDOVER + " is not valid UTF-8");
        }
        var env = new HashMap<String, String>();
        var lineNo = 0;
        for (var rawLine : lines) {
            lineNo++;
            var line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var eq = line.indexOf('=');
            if (eq <= 0 || !ENV_NAME.matcher(line.substring(0, eq)).matches()) {
                throw new Rejected("Line %d of $%s is not of the form NAME=value".formatted(lineNo, ENV_HANDOVER));
            }
            var name = line.substring(0, eq);
            if (name.startsWith("TECHNOLINATOR_")) {
                throw new Rejected("Line %d of $%s sets %s, but names starting with TECHNOLINATOR_ are reserved".formatted(lineNo, ENV_HANDOVER, name));
            }
            if (line.indexOf(0) >= 0) {
                throw new Rejected("Line %d of $%s contains a NUL character".formatted(lineNo, ENV_HANDOVER));
            }
            env.put(name, line.substring(eq + 1));
        }
        return env;
    }

    private record Setup(Map<String, String> environment, Path script, Path handover, Path scratch) {
    }

    /**
     * The preparation violated a rule, that is the repository's fault.
     */
    static final class Rejected extends Exception {
        Rejected(String message) {
            super(message);
        }
    }

    private static final class Foreign extends IOException {
        Foreign(String message) {
            super(message);
        }
    }

    /**
     * The private working area of a single analysis. Created on demand, removed once the analysis terminated for whatever reason.
     */
    static final class Scratch {
        private Path directory;
        private boolean closed;

        synchronized Path create() throws IOException {
            if (closed) {
                throw new IllegalStateException("The preparation was cancelled");
            }
            if (directory != null) {
                throw new IllegalStateException("The scratch directory is created already");
            }
            directory = Files.createTempDirectory("technolinator-prepare-");
            return directory;
        }

        synchronized void close() {
            closed = true;
            if (directory != null) {
                // the script might have been cancelled, e.g. by the timeout of the analysis, leaving its background processes behind
                killLeftovers(directory);
                Util.removeAsync(directory);
                directory = null;
            }
        }
    }
}
