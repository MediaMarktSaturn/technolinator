package com.mediamarktsaturn.ghbot.git;

import java.io.File;

import org.eclipse.jgit.api.Git;

import com.mediamarktsaturn.ghbot.os.Util;
import io.quarkus.logging.Log;

public record LocalRepository(
    Git repo,
    File dir
) implements AutoCloseable {

    @Override
    public void close() {
        if (repo != null) {
            try {
                repo.close();
            } catch (Exception e) {
                Log.warn("Error closing Git repo", e);
            }
            Util.removeAsync(dir);
        }
    }
}
