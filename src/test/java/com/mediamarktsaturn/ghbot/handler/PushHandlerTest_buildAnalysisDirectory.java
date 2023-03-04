package com.mediamarktsaturn.ghbot.handler;


import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.ghbot.ConfigBuilder;
import com.mediamarktsaturn.ghbot.git.LocalRepository;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class PushHandlerTest_buildAnalysisDirectory {

    @Test
    public void testConfigless() {
        // Given
        var repo = new LocalRepository(new File("test_tmp"));

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.empty());

        // Then
        assertThat(location).isEqualTo(repo.dir());
    }

    @Test
    public void testConfigRelative() {
        // Given
        var repo = new LocalRepository(new File("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig("sub_dir", null, List.of())).build();


        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp/sub_dir"));
    }

    @Test
    public void testConfigAbsolute() {
        // Given
        var repo = new LocalRepository(new File("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig("/sub_dir", true, List.of())).build();

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp/sub_dir"));
    }

    @Test
    public void testConfigRoot() {
        // Given
        var repo = new LocalRepository(new File("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig("/", false, List.of())).build();

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp", "/"));
    }

    @Test
    public void testConfigEmpty() {
        // Given
        var repo = new LocalRepository(new File("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig("", null, List.of())).build();

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp"));
    }

    @Test
    public void testConfigNull() {
        // Given
        var repo = new LocalRepository(new File("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, null, List.of())).build();

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(new File("test_tmp"));
    }
}
