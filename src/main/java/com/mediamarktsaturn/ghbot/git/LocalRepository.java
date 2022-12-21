package com.mediamarktsaturn.ghbot.git;

import java.io.File;
import java.util.Arrays;

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

    public Type determineType() {
        return Type.determine(dir);
    }

    public enum Type {
        MAVEN("pom.xml"),
        // order relevant for precedence in detection, UNKNOWN has to come last
        UNKNOWN(".");

        final String badge;

        Type(String badge) {
            this.badge = badge;
        }

        private static Type determine(File dir) {
            return Arrays.stream(Type.values()).filter(t -> t.matches(dir)).findFirst().orElse(UNKNOWN);
        }

        private boolean matches(File dir) {
            var badgeFile = new File(dir, badge);
            return badgeFile.exists() && badgeFile.canRead();
        }
    }
}
