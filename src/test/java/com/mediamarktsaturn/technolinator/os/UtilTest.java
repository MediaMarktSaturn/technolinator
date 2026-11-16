package com.mediamarktsaturn.technolinator.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UtilTest {

    @TempDir
    Path dir;

    @Test
    void removesDirectoryTreeIncludingContent() throws IOException {
        // Given
        var tree = Files.createDirectories(dir.resolve("a/b/c"));
        Files.writeString(tree.resolve("file.txt"), "content");
        Files.writeString(dir.resolve("a/other.txt"), "content");

        // When
        Util.removeRecursively(dir.resolve("a"));

        // Then
        assertThat(dir.resolve("a")).doesNotExist();
        assertThat(dir).exists();
    }

    @Test
    void ignoresMissingPath() throws IOException {
        Util.removeRecursively(dir.resolve("never-existed"));

        assertThat(dir).exists();
    }

    @Test
    void doesNotFollowSymbolicLinks() throws IOException {
        // Given
        var outside = Files.createDirectory(dir.resolve("outside"));
        var precious = Files.writeString(outside.resolve("precious.txt"), "keep me");
        var tree = Files.createDirectory(dir.resolve("tree"));
        Files.createSymbolicLink(tree.resolve("link"), outside);

        // When
        Util.removeRecursively(tree);

        // Then
        assertThat(tree).doesNotExist();
        assertThat(precious).hasContent("keep me");
    }
}
