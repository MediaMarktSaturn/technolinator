package com.mediamarktsaturn.technolinator.os;

import java.nio.file.Path;

import com.mediamarktsaturn.technolinator.Commons;

/**
 * Common functionalities regarding the operating system
 */
public interface Util {
    static void removeAsync(Path file) {
        ProcessHandler.run("rm -rf " + file.toAbsolutePath())
            .subscribe().withSubscriber(Commons.NOOP_SUBSCRIBER);
    }
}
