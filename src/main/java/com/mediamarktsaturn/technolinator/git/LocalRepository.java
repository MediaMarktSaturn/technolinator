package com.mediamarktsaturn.technolinator.git;

import java.nio.file.Path;

import com.mediamarktsaturn.technolinator.os.Util;

/**
 * The local representation of a git repository, will be removed from local storage on close.
 * @param dir
 */
public record LocalRepository(
    Path dir
) implements AutoCloseable {

    @Override
    public void close() {
        Util.removeAsync(dir);
    }
}
