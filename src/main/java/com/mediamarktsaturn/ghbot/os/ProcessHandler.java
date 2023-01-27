package com.mediamarktsaturn.ghbot.os;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.unchecked.Unchecked;

public class ProcessHandler {

    public static final File CURRENT_DIR = new File(".");

    public static Uni<ProcessResult> run(String command) {
        return run(command, CURRENT_DIR, Map.of(), new ProcessCallback.DefaultProcessCallback());
    }

    public static Uni<ProcessResult> run(String command, File workingDir, Map<String, String> env) {
        return run(command, workingDir, env, new ProcessCallback.DefaultProcessCallback());
    }

    public static Uni<ProcessResult> run(String command, File workingDir, Map<String, String> env, ProcessCallback callback) {
        Log.infof("[%s] Starting '%s' in %s", callback.getIdent(), command, workingDir);
        List<String> outputLines = new ArrayList<>();
        return Uni.createFrom().completionStage(Unchecked.supplier(() -> {
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
                return process.onExit();
            })
        ).map(process -> {
            int exit = process.exitValue();
            callback.onComplete(exit);
            if (exit != 0) {
                return (ProcessResult) new ProcessResult.Failure(outputLines, exit, null);
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
