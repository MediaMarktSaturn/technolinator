package com.mediamarktsaturn.technolinator.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryServiceExtractionTest {

    @TempDir
    Path dir;

    @Test
    void unwrapsTheWrappingFolder() throws IOException {
        // Given
        var zip = zip(Map.of(
            "wrapper-123/", "",
            "wrapper-123/pom.xml", "<project/>",
            "wrapper-123/src/", "",
            "wrapper-123/src/Main.java", "class Main {}",
            // directory entries are not guaranteed to be present
            "wrapper-123/deep/nested/file.txt", "content"
        ));

        // When
        RepositoryService.extractToDirectory(zip, dir);

        // Then
        assertThat(dir.resolve("pom.xml")).hasContent("<project/>");
        assertThat(dir.resolve("src/Main.java")).hasContent("class Main {}");
        assertThat(dir.resolve("deep/nested/file.txt")).hasContent("content");
    }

    @Test
    void rejectsEntriesOutsideOfTheTargetDirectory() throws IOException {
        // Given
        var sub = Files.createDirectory(dir.resolve("sub"));
        var zip = zip(Map.of(
            "wrapper/", "",
            "wrapper/../../escaped.txt", "gotcha"
        ));

        // When & Then
        assertThatThrownBy(() -> RepositoryService.extractToDirectory(zip, sub))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside");
        assertThat(dir.resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void rejectsEntriesOutsideOfTheWrappingFolder() throws IOException {
        var zip = zip(Map.of(
            "wrapper/", "",
            "wrapperX/file.txt", "misplaced"
        ));

        assertThatThrownBy(() -> RepositoryService.extractToDirectory(zip, dir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("wrapping folder");
    }

    @Test
    void rejectsZipWithoutWrappingFolder() throws IOException {
        var zip = zip(Map.of("pom.xml", "<project/>"));

        assertThatThrownBy(() -> RepositoryService.extractToDirectory(zip, dir))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("folder");
    }

    @Test
    void rejectsEmptyZip() {
        var zip = new ZipInputStream(new ByteArrayInputStream(new byte[0]));

        assertThatThrownBy(() -> RepositoryService.extractToDirectory(zip, dir))
            .isInstanceOf(IllegalStateException.class);
    }

    private static ZipInputStream zip(Map<String, String> entries) throws IOException {
        // keep the given order, the wrapping folder has to come first
        var ordered = new LinkedHashMap<String, String>();
        entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> ordered.put(e.getKey(), e.getValue()));
        var out = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(out)) {
            for (var entry : ordered.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    }
}
