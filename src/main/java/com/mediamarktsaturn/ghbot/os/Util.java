package com.mediamarktsaturn.ghbot.os;

import java.io.File;

import io.quarkus.logging.Log;

public class Util {
    public static void removeAsync(File dir) {
        ProcessHandler.run("rm -rf " + dir.getAbsolutePath())
            .subscribe().with(
                item -> {},
                failure -> Log.warn("Error removing tmp dir", failure)
            );
    }
}