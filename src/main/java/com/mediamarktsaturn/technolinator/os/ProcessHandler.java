package com.mediamarktsaturn.technolinator.os;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Runs operating system commands
 */
public class ProcessHandler {

    public static final Path CURRENT_DIR = Paths.get(".");

    /**
     * Upper bound of output lines kept per process, the oldest ones are dropped first.
     * Protects against verbose tools (e.g. in debug mode) exhausting the heap.
     */
    static final int MAX_OUTPUT_LINES = 20_000;

    public static Uni<ProcessResult> run(String command) {
        return run(command, CURRENT_DIR, Map.of(), new ProcessCallback.DefaultProcessCallback());
    }

    public static Uni<ProcessResult> run(String command, Path workingDir, Map<String, String> env) {
        return run(command, workingDir, env, new ProcessCallback.DefaultProcessCallback());
    }

    static Uni<ProcessResult> run(String command, Path workingDir, Map<String, String> env, ProcessCallback callback) {
        // deferred, so that every subscription (e.g. a retry) runs its own process with its own state
        return Uni.createFrom().deferred(() -> start(command, workingDir, env, false, callback));
    }

    /**
     * Runs the command with exactly the given environment, nothing is inherited from the current process.
     */
    public static Uni<ProcessResult> runIsolated(String command, Path workingDir, Map<String, String> env) {
        return runIsolated(command, workingDir, env, new ProcessCallback.DefaultProcessCallback());
    }

    static Uni<ProcessResult> runIsolated(String command, Path workingDir, Map<String, String> env, ProcessCallback callback) {
        return Uni.createFrom().deferred(() -> start(command, workingDir, env, true, callback));
    }

    private static Uni<ProcessResult> start(String command, Path workingDir, Map<String, String> env, boolean isolated, ProcessCallback callback) {
        callback.log("Starting '%s' in %s".formatted(command, workingDir));
        var outputLines = new ArrayDeque<String>();
        var process = new AtomicReference<Process>();
        var cancelled = new AtomicBoolean();
        return Uni.createFrom().item(
                Unchecked.supplier(() -> {
                    var commandParts = command.trim().split("\\s+");
                    var processBuilder = new ProcessBuilder(commandParts)
                        .directory(workingDir.toFile())
                        .redirectErrorStream(true);
                    if (isolated) {
                        processBuilder.environment().clear();
                    }
                    processBuilder.environment().putAll(env);

                    var started = processBuilder.start();
                    process.set(started);
                    if (cancelled.get()) {
                        kill(started);
                    }

                    // the cdxgen environment audit is of no use, neither in the log nor in reports
                    var auditFilter = new SecureModeAuditFilter();
                    Consumer<String> sink = line -> {
                        callback.onOutput(line);
                        if (outputLines.size() >= MAX_OUTPUT_LINES) {
                            outputLines.removeFirst();
                        }
                        outputLines.addLast(line);
                    };
                    try (var output = new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                        String line;
                        while ((line = output.readLine()) != null) {
                            auditFilter.accept(line, sink);
                        }
                        auditFilter.flush(sink);
                    }
                    return started.waitFor();
                })
            ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            // a timed out or otherwise abandoned analysis must not leave its processes behind
            .onCancellation().invoke(() -> {
                cancelled.set(true);
                kill(process.get());
            })
            .map(exit -> {
                callback.onComplete(exit);
                if (exit != 0) {
                    var lines = List.copyOf(outputLines);
                    callback.onFailureOutput(lines);
                    return (ProcessResult) new ProcessResult.Failure(lines, exit, new ProcessFailedException(command, exit, lines));
                } else {
                    return (ProcessResult) new ProcessResult.Success(List.copyOf(outputLines));
                }
            }).onFailure().recoverWithItem(failure -> {
                callback.onFailure(failure);
                var lines = List.copyOf(outputLines);
                callback.onFailureOutput(lines);
                return (ProcessResult) new ProcessResult.Failure(lines, null, failure);
            });
    }

    /**
     * Terminates the process including the processes it spawned, e.g. the build tools started by cdxgen
     */
    private static void kill(Process process) {
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    public sealed interface ProcessResult {
        record Success(
            List<String> outputLines
        ) implements ProcessResult {
        }

        record Failure(
            List<String> outputLines,
            Integer exitCode,
            Throwable cause
        ) implements ProcessResult {
        }
    }

}
