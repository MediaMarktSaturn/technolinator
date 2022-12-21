package com.mediamarktsaturn.ghbot.os;

import java.util.UUID;

import io.quarkus.logging.Log;

public interface ProcessCallback {

    void onComplete(int exitStatus);

    void onOutput(String logLine);

    void onFailure(Throwable failure);

    class DefaultProcessCallback implements ProcessCallback {

        private final String command;
        private final String ident;

        public DefaultProcessCallback(String command) {
            this.command = command;
            this.ident = UUID.randomUUID().toString().substring(0, 8);
        }

        @Override
        public void onComplete(int exitStatus) {
            if (exitStatus == 0) {
                Log.infof("%s#[%s] - succeeded", ident, command);
            } else {
                Log.warnf("%s#[%s] - failed (%s)", ident, command, exitStatus);
            }
        }

        @Override
        public void onOutput(String logLine) {
            Log.infof("%s#[%s]: %s", ident, command, logLine);
        }

        @Override
        public void onFailure(Throwable failure) {
            Log.errorf(failure, "%s#[%s] - failed: %s", ident, command, failure.getMessage());
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
    };
}
