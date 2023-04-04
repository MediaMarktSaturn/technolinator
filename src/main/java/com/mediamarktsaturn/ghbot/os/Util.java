package com.mediamarktsaturn.ghbot.os;

import java.nio.file.Path;

import com.mediamarktsaturn.ghbot.Commons;

public interface Util {
    static void removeAsync(Path file) {
        ProcessHandler.run("rm -rf " + file.toAbsolutePath())
            .subscribe().withSubscriber(Commons.NOOP_SUBSCRIBER);
    }
}
