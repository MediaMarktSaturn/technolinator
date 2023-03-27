package com.mediamarktsaturn.ghbot.git;

import java.nio.file.Path;

import com.mediamarktsaturn.ghbot.os.Util;

public record LocalRepository(
    Path dir
) implements AutoCloseable {

    @Override
    public void close() {
        Util.removeAsync(dir);
    }
}
