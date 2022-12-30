package com.mediamarktsaturn.ghbot.git;

import java.io.File;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("resource")
public class LocalRepositoryTest {

    @Test
    public void testDetermineMaven() {
        // Given
        var repo = new LocalRepository(null, new File("src/test/resources/repo/maven"));

        // When
        var type = repo.determineType();

        // Then
        assertThat(type).isEqualTo(LocalRepository.Type.MAVEN);
    }

    @Test
    public void testDetermineUnknown() {
        // Given
        var repo = new LocalRepository(null, new File("src/test/resources/repo"));

        // When
        var type = repo.determineType();

        // Then
        assertThat(type).isEqualTo(LocalRepository.Type.UNKNOWN);
    }

    @Test
    public void testDetermineMixed() {
        // Given
        var repo = new LocalRepository(null, new File("src/test/resources/repo/multi-mode"));

        // When
        var type = repo.determineType();

        // Then
        // TODO: we should remove the type inspection completely or check
        //  if we introduce a list of types / multi-mode
        assertThat(type).isEqualTo(LocalRepository.Type.MAVEN);
    }
}
