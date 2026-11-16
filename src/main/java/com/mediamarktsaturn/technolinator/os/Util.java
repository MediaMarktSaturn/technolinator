package com.mediamarktsaturn.technolinator.os;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Common functionalities regarding the operating system
 */
public interface Util {

    /**
     * Removes the given file or directory in the background, failures are logged only.
     */
    static void removeAsync(Path file) {
        Infrastructure.getDefaultWorkerPool().execute(() -> {
            try {
                removeRecursively(file);
            } catch (IOException | RuntimeException e) {
                Log.warnf(e, "Failed to remove %s", file);
            }
        });
    }

    /**
     * Removes the given file or the directory including its content, doing nothing if it doesn't exist.
     * Symbolic links are removed themselves, never followed.
     */
    static void removeRecursively(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
