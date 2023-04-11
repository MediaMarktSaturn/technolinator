package com.mediamarktsaturn.technolinator.handler;


import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.git.LocalRepository;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class PushHandlerTest_buildAnalysisDirectory {

    @Test
    void testConfigless() {
        // Given
        var repo = new LocalRepository(Paths.get("test_tmp"));

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.empty());

        // Then
        assertThat(location).isEqualTo(repo.dir());
    }

    @Test
    void testConfigRelative() {
        // Given
        var repo = new LocalRepository(Paths.get("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig("sub_dir", null, List.of())).build();


        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(Paths.get("test_tmp/sub_dir"));
    }

    @Test
    void testConfigAbsolute() {
        // Given
        var repo = new LocalRepository(Paths.get("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig("/sub_dir", true, List.of())).build();

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(Paths.get("test_tmp/sub_dir"));
    }

    @Test
    void testConfigRoot() {
        // Given
        var repo = new LocalRepository(Paths.get("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig("/", false, List.of())).build();

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(Paths.get("test_tmp", "/"));
    }

    @Test
    void testConfigEmpty() {
        // Given
        var repo = new LocalRepository(Paths.get("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig("", null, List.of())).build();

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(Paths.get("test_tmp"));
    }

    @Test
    void testConfigNull() {
        // Given
        var repo = new LocalRepository(Paths.get("test_tmp"));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(null, null, List.of())).build();

        // When
        var location = PushHandler.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(Paths.get("test_tmp"));
    }
}
