package com.mediamarktsaturn.ghbot.os;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.quarkus.logging.Log;

public interface ProcessCallback {

    void onComplete(int exitStatus);

    void onOutput(String logLine);

    void onFailure(Throwable failure);

    String getIdent();

    class DefaultProcessCallback implements ProcessCallback {

        private static final String SENSITIVE_ENV_VARS = System.getenv("SENSITIVE_ENV_VARS");

        private final String ident;
        private Map<String, String> sensitiveEnv = Collections.emptyMap();


        @Override
        public String getIdent() {
            return ident;
        }

        public DefaultProcessCallback() {
            this.ident = UUID.randomUUID().toString().substring(0, 8);

            if (SENSITIVE_ENV_VARS != null) {
                var sensitiveEnvKeys = Arrays.stream(SENSITIVE_ENV_VARS.split(",")).map(String::trim).toList();
                this.sensitiveEnv = System.getenv().entrySet().stream()
                    .filter(e -> sensitiveEnvKeys.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }
        }

        @Override
        public void onComplete(int exitStatus) {
            if (exitStatus == 0) {
                Log.infof("[%s] succeeded", ident);
            } else {
                Log.warnf("[%s] failed (%s)", ident, exitStatus);
            }
        }

        @Override
        public void onOutput(String logLine) {
            // hide (sensitive) env values from output
            for (var env : sensitiveEnv.entrySet()) {
                logLine = logLine.replace(env.getValue(), "*" + env.getKey() + "*");
            }
            Log.debugf("[%s]: %s", ident, logLine);
        }

        @Override
        public void onFailure(Throwable failure) {
            Log.errorf(failure, "[%s] failed: %s", ident, failure.getMessage());
        }
    }

    ProcessCallback NOOP = new ProcessCallback() {
        @Override
        public void onComplete(int exitStatus) {
        }

        @Override
        public void onOutput(String logLine) {
        }

        @Override
        public void onFailure(Throwable failure) {
        }

        @Override
        public String getIdent() {
            return "";
        }
    };
}
