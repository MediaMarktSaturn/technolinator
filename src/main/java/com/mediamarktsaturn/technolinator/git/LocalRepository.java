package com.mediamarktsaturn.technolinator.git;

import com.mediamarktsaturn.technolinator.os.Util;

import java.nio.file.Path;

/**
 * The local representation of a git repository, will be removed from local storage on close.
 *
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
