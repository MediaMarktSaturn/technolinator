package com.mediamarktsaturn.technolinator.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.git.LocalRepository;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class HandlerTest {

    private static Stream<Arguments> locationProvider() {
        return Stream.of(
            // given location, expected location
            Arguments.of("sub_dir", "/sub_dir"),
            Arguments.of("sub_dir/subst_dir", "/sub_dir/subst_dir"),
            Arguments.of("/sub_dir", "/sub_dir"),
            Arguments.of("/", ""),
            Arguments.of("", ""),
            Arguments.of(null, "")
        );
    }

    @Test
    void testConfigless() {
        // Given
        var repo = new LocalRepository(Paths.get("test_tmp"));

        // When
        var location = HandlerBase.buildAnalysisDirectory(repo, Optional.empty());

        // Then
        assertThat(location).isEqualTo(repo.dir());
    }

    @ParameterizedTest(name = "{index} => given location: {0}, expected location: <base-path>{1}")
    @MethodSource("locationProvider")
    void testConfigRelative(String givenLocation, String expectedLocation) {
        // Given
        var path = "test_tmp";
        var repo = new LocalRepository(Paths.get(path));
        var config = ConfigBuilder.create().analysis(new TechnolinatorConfig.AnalysisConfig(givenLocation, null, List.of())).build();


        // When
        var location = HandlerBase.buildAnalysisDirectory(repo, Optional.of(config));

        // Then
        assertThat(location).isEqualTo(Paths.get(path + expectedLocation));
    }

}
