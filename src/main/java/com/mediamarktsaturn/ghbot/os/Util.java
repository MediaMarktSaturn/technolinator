package com.mediamarktsaturn.ghbot.os;

import java.io.File;

import io.quarkus.logging.Log;

public class Util {
    public static void removeAsync(File dir) {
        try {
            ProcessHandler.run("rm -rf " + dir.getAbsolutePath());
        } catch (Exception e) {
            Log.warn("Error removing tmp dir", e);
        }
    }
}
