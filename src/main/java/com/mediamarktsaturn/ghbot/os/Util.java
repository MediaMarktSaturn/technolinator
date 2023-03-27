package com.mediamarktsaturn.ghbot.os;

import java.nio.file.Path;

import io.quarkus.logging.Log;

public interface Util {
    static void removeAsync(Path file) {
        ProcessHandler.run("rm -rf " + file.toAbsolutePath())
            .subscribe().with(
                item -> {
                },
                failure -> Log.warn("Error removing tmp dir", failure)
            );
    }
}
