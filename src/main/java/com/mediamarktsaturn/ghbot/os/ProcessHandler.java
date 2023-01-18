package com.mediamarktsaturn.ghbot.os;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ProcessHandler {

    public static final File CURRENT_DIR = new File(".");
    private static final ExecutorService EXECUTORS = Executors.newCachedThreadPool();

    public static CompletableFuture<ProcessResult> run(String command) {
        return run(command, CURRENT_DIR, Map.of(), new ProcessCallback.DefaultProcessCallback(command));
    }

    public static CompletableFuture<ProcessResult> run(String command, File workingDir, Map<String, String> env) {
        return run(command, workingDir, env, new ProcessCallback.DefaultProcessCallback(command));
    }

    public static CompletableFuture<ProcessResult> run(String command, File workingDir, Map<String, String> env, ProcessCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> outputLines = new ArrayList<>();
            try {
                var commandParts = command.trim().split("\\s+");
                var processBuilder = new ProcessBuilder(commandParts)
                    .directory(workingDir)
                    .redirectErrorStream(true);
                processBuilder.environment().putAll(env);

                var process = processBuilder.start();
                var output = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = output.readLine()) != null) {
                    callback.onOutput(line);
                    outputLines.add(line);
                }

                process.waitFor(5, TimeUnit.SECONDS);
                int exit = process.exitValue();
                callback.onComplete(exit);
                if (exit != 0) {
                    return new ProcessResult.Failure(outputLines, exit, null);
                } else {
                    return new ProcessResult.Success(outputLines);
                }
            } catch (Exception e) {
                callback.onFailure(e);
                return new ProcessResult.Failure(outputLines, null, e);
            }
        }, EXECUTORS);
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
