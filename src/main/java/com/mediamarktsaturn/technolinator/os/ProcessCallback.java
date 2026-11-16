package com.mediamarktsaturn.technolinator.os;

import com.mediamarktsaturn.technolinator.Command;
import io.quarkus.logging.Log;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Receiver on async-provided information for a [ProcessHandler] execution
 */
public interface ProcessCallback {

    void onComplete(int exitStatus);

    void onOutput(String logLine);

    void onFailure(Throwable failure);

    void log(String message);

    class DefaultProcessCallback implements ProcessCallback {

        /**
         * Values of the env variables named by SENSITIVE_ENV_VARS (comma separated), to be hidden in the output.
         * Blank values are ignored, as replacing them would garble every single line.
         */
        private static final Map<String, String> SENSITIVE_ENV = Optional.ofNullable(System.getenv("SENSITIVE_ENV_VARS"))
            .map(names -> Arrays.stream(names.split(",")).map(String::trim).collect(Collectors.toSet()))
            .map(names -> System.getenv().entrySet().stream()
                .filter(e -> names.contains(e.getKey()) && !e.getValue().isBlank())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue)))
            .orElse(Map.of());

        private final Command.Metadata metadata;
        private final long start;

        public DefaultProcessCallback() {
            this.metadata = Command.Metadata.readFromMDC();
            this.start = System.currentTimeMillis();
        }

        @Override
        public void onComplete(int exitStatus) {
            var duration = Duration.ofMillis(System.currentTimeMillis() - start);
            metadata.writeToMDC();
            if (exitStatus == 0) {
                Log.infof("[%s] succeeded after %s", metadata.traceId(), duration);
            } else {
                Log.warnf("[%s] failed (%s) after %s", metadata.traceId(), exitStatus, duration);
            }
        }

        @Override
        public void onOutput(String logLine) {
            if (Log.isDebugEnabled()) {
                metadata.writeToMDC();
                // hide (sensitive) env values from output
                for (var env : SENSITIVE_ENV.entrySet()) {
                    logLine = logLine.replace(env.getValue(), "*" + env.getKey() + "*");
                }
                Log.debugf("[%s]: %s", metadata.traceId(), logLine);
            }
        }

        @Override
        public void onFailure(Throwable failure) {
            metadata.writeToMDC();
            Log.errorf(failure, "[%s] failed: %s", metadata.traceId(), failure.getMessage());
        }

        @Override
        public void log(String message) {
            metadata.writeToMDC();
            Log.infof("[%s] %s", metadata.traceId(), message);
        }
    }

    ProcessCallback NOOP = new ProcessCallback() {
        @Override
        public void onComplete(int exitStatus) {
            // no logging
        }

        @Override
        public void onOutput(String logLine) {
            // no logging
        }

        @Override
        public void onFailure(Throwable failure) {
            // no logging
        }

        @Override
        public void log(String message) {
            Log.info(message);
        }
    };
}
