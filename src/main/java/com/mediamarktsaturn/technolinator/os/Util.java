package com.mediamarktsaturn.technolinator.os;

import com.mediamarktsaturn.technolinator.Commons;

import java.nio.file.Path;

/**
 * Common functionalities regarding the operating system
 */
public interface Util {
    static void removeAsync(Path file) {
        ProcessHandler.run("rm -rf " + file.toAbsolutePath())
            .subscribe().withSubscriber(Commons.NOOP_SUBSCRIBER);
    }
}
