package com.mediamarktsaturn.technolinator.os;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;

/**
 * Runs operating system commands
 */
public class ProcessHandler {

    public static final Path CURRENT_DIR = Paths.get(".");

    public static Uni<ProcessResult> run(String command) {
        return run(command, CURRENT_DIR, Map.of(), new ProcessCallback.DefaultProcessCallback());
    }

    public static Uni<ProcessResult> run(String command, Path workingDir, Map<String, String> env) {
        return run(command, workingDir, env, new ProcessCallback.DefaultProcessCallback());
    }

    static Uni<ProcessResult> run(String command, Path workingDir, Map<String, String> env, ProcessCallback callback) {
        callback.log("Starting '%s' in %s".formatted(command, workingDir));
        List<String> outputLines = new ArrayList<>();
        return Uni.createFrom().item(
                Unchecked.supplier(() -> {
                    var commandParts = command.trim().split("\\s+");
                    var processBuilder = new ProcessBuilder(commandParts)
                        .directory(workingDir.toFile())
                        .redirectErrorStream(true);
                    processBuilder.environment().putAll(env);

                    var process = processBuilder.start();
                    var output = new BufferedReader(new InputStreamReader(process.getInputStream()));

                    String line;
                    while ((line = output.readLine()) != null) {
                        callback.onOutput(line);
                        outputLines.add(line);
                    }
                    return process.waitFor();
                })
            ).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .map(exit -> {
                callback.onComplete(exit);
                if (exit != 0) {
                    return (ProcessResult) new ProcessResult.Failure(outputLines, exit, new Exception(command + " exited with " + exit));
                } else {
                    return (ProcessResult) new ProcessResult.Success(outputLines);
                }
            }).onFailure().recoverWithItem(failure -> {
                callback.onFailure(failure);
                return (ProcessResult) new ProcessResult.Failure(outputLines, null, failure);
            });
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
