package com.mediamarktsaturn.ghbot.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;

import org.junit.jupiter.api.Test;

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
}
