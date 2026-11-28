package com.mediamarktsaturn.technolinator.os;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
     * Directories lacking permissions of the owner (like the read-only module cache of Go) get them granted first, so that they can be emptied.
     */
    static void removeRecursively(Path path) throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return;
        }
        if (attrs.isDirectory()) {
            grantOwnerAccess(path);
            List<Path> children;
            try (var entries = Files.list(path)) {
                children = entries.toList();
            }
            for (var child : children) {
                removeRecursively(child);
            }
        }
        Files.deleteIfExists(path);
    }

    private static void grantOwnerAccess(Path directory) {
        try {
            var permissions = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
            if (!permissions.containsAll(OWNER_ACCESS)) {
                permissions.addAll(OWNER_ACCESS);
                Files.setPosixFilePermissions(directory, permissions);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // not the owner or no POSIX file system, removing the content will tell if it matters
        }
    }

    Set<PosixFilePermission> OWNER_ACCESS = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
}
