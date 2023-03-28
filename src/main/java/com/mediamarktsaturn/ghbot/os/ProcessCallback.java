package com.mediamarktsaturn.ghbot.os;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import com.mediamarktsaturn.ghbot.Command;
import io.quarkus.logging.Log;

public interface ProcessCallback {

    void onComplete(int exitStatus);

    void onOutput(String logLine);

    void onFailure(Throwable failure);

    void log(String message);

    class DefaultProcessCallback implements ProcessCallback {

        private static final String SENSITIVE_ENV_VARS = System.getenv("SENSITIVE_ENV_VARS");

        private final Command.Metadata metadata;
        private final long start;
        private Map<String, String> sensitiveEnv = Collections.emptyMap();

        public DefaultProcessCallback() {
            this.metadata = Command.Metadata.readFromMDC();

            if (SENSITIVE_ENV_VARS != null) {
                var sensitiveEnvKeys = Arrays.stream(SENSITIVE_ENV_VARS.split(",")).map(String::trim).toList();
                this.sensitiveEnv = System.getenv().entrySet().stream()
                    .filter(e -> sensitiveEnvKeys.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }

            start = System.currentTimeMillis();
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
                for (var env : sensitiveEnv.entrySet()) {
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
