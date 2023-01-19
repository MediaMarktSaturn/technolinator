package com.mediamarktsaturn.ghbot.os;

import java.util.UUID;

import io.quarkus.logging.Log;

public interface ProcessCallback {

    void onComplete(int exitStatus);

    void onOutput(String logLine);

    void onFailure(Throwable failure);

    String getIdent();

    class DefaultProcessCallback implements ProcessCallback {

        private final String ident;

        @Override
        public String getIdent() {
            return ident;
        }

        public DefaultProcessCallback() {
            this.ident = UUID.randomUUID().toString().substring(0, 8);
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
            Log.infof("[%s]: %s", ident, logLine);
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
