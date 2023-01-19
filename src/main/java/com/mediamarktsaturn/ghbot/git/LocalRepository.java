package com.mediamarktsaturn.ghbot.git;

import java.io.File;

import com.mediamarktsaturn.ghbot.os.Util;

public record LocalRepository(
    File dir
) implements AutoCloseable {

    @Override
    public void close() {
        Util.removeAsync(dir);
    }
}
