package com.mediamarktsaturn.ghbot.handler;


import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.ghbot.git.LocalRepository;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;

public class PushHandlerTest_buildAnalysisDirectory {

    @Test
    public void testConfigless() {
        // Given
        var repo = new LocalRepository(null, new File("test_tmp"));

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.empty());

        // Then
        assertThat(location).isEqualTo(repo.dir());
    }

    @Test
    public void testConfigRelative() {
        // Given
        var repo = new LocalRepository(null, new File("test_tmp"));
        var config = new TechnolinatorConfig(null, null, new TechnolinatorConfig.AnalysisConfig("sub_dir"));

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp/sub_dir"));
    }

    @Test
    public void testConfigAbsolute() {
        // Given
        var repo = new LocalRepository(null, new File("test_tmp"));
        var config = new TechnolinatorConfig(null, null, new TechnolinatorConfig.AnalysisConfig("/sub_dir"));

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp/sub_dir"));
    }

    @Test
    public void testConfigRoot() {
        // Given
        var repo = new LocalRepository(null, new File("test_tmp"));
        var config = new TechnolinatorConfig(null, null, new TechnolinatorConfig.AnalysisConfig("/"));

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp", "/"));
    }

    @Test
    public void testConfigEmpty() {
        // Given
        var repo = new LocalRepository(null, new File("test_tmp"));
        var config = new TechnolinatorConfig(null, null, new TechnolinatorConfig.AnalysisConfig(""));

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp"));
    }

    @Test
    public void testConfigNull() {
        // Given
        var repo = new LocalRepository(null, new File("test_tmp"));
        var config = new TechnolinatorConfig(null, null, new TechnolinatorConfig.AnalysisConfig(null));

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp"));
    }
}
