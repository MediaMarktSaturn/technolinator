package com.mediamarktsaturn.ghbot.sbom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.ghbot.ConfigBuilder;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class CdxgenClientEnvTest {

    @Inject
    CdxgenClient cut;

    @Test
    void testSingleEnvValueSubstitution() {
        // Given
        var value = "-PdingsTeil=${test_Env1}";

        // When
        var result = CdxgenClient.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil=this's just a test");
    }

    @Test
    void testMultipleEnvValueSubstitutions() {
        // Given
        var value = "-PdingsTeil=${test_Env1} -Doh=${test_Env2} --yeah=${test_Env1}";

        // When
        var result = CdxgenClient.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil=this's just a test -Doh=oh_yeah-look_at_me --yeah=this's just a test");
    }

    @Test
    void testNoneEnvValueSubstitutions() {
        // Given
        var value = "-PdingsTeil=${never_WILL_be_there} $ever ";

        // When
        var result = CdxgenClient.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil= $ever ");
    }

    @Test
    void testGradleEnv() {
        // Given
        var config = ConfigBuilder.create().gradle(new TechnolinatorConfig.GradleConfig(
            false,
            List.of(
                "-PartifactoryUser=\"${test_Env1}\"",
                "-DartifactoryPassword=${test_Env2}",
                "-BgoAway=${withThis}"
            ))
        ).build();

        // When
        var result = cut.buildEnv(Optional.of(config));

        // Then
        assertThat(result)
            .containsEntry("GRADLE_ARGS", "-PartifactoryUser=\"this's just a test\" -DartifactoryPassword=oh_yeah-look_at_me -BgoAway=");
    }

    @Test
    void testGradleMultiProjectEnv() {
        // Given
        var config = ConfigBuilder.create().gradle(new TechnolinatorConfig.GradleConfig(
            true,
            List.of())
        ).build();

        // When
        var result = cut.buildEnv(Optional.of(config));

        // Then
        assertThat(result)
            .containsEntry("GRADLE_MULTI_PROJECT_MODE", "true");
    }

    @Test
    void testMavenEnv() {
        // Given
        var config = ConfigBuilder.create().maven(new TechnolinatorConfig.MavenConfig(
            List.of(
                "-P\"${test_Env1}\"",
                "-DRUN=${test_Env2}",
                "-BgoAway=${withThis}"
            ))
        ).build();

        // When
        var result = cut.buildEnv(Optional.of(config));

        // Then
        assertThat(result)
            .containsEntry("MVN_ARGS", "-B -ntp -P\"this's just a test\" -DRUN=oh_yeah-look_at_me -BgoAway=");
    }

    @Test
    void testMixedArgs() {
        // Given
        var config = ConfigBuilder.create().maven(new TechnolinatorConfig.MavenConfig(
                List.of("maven (${test_Env1})"))
            ).gradle(new TechnolinatorConfig.GradleConfig(
                false,
                List.of("gradle {${test_Env2}}"))
            ).env(Map.of("one", "ten"))
            .build();

        // When
        var result = cut.buildEnv(Optional.of(config));

        // Then
        assertThat(result)
            .containsEntry("one", "ten")
            .containsEntry("GRADLE_ARGS", "gradle {oh_yeah-look_at_me}")
            .containsEntry("MVN_ARGS", "-B -ntp maven (this's just a test)");
    }

    @Test
    void testWithoutArgs() {
        // When
        var result = cut.buildEnv(Optional.empty());

        // Then
        assertThat(result)
            .doesNotContainKey("GRADLE_ARGS")
            .containsEntry("MVN_ARGS", "-B -ntp")
            .containsEntry("JAVA_HOME", System.getenv("JAVA_HOME"));
    }

    @Test
    void testJdkVersionSelection() {
        // Given
        var config = ConfigBuilder.create()
            .jdk(new TechnolinatorConfig.JdkConfig("20"))
            .build();

        // When
        var result = cut.buildEnv(Optional.of(config));

        // Then
        assertThat(result).containsEntry("JAVA_HOME", "/path/to/jdk20");
    }

    @Test
    void testEnv() {
        // Given
        var config = ConfigBuilder.create()
            .env(Map.of("YEHAA", "oh ${test_Env2} ha", "dings", "bums"))
            .build();

        // When
        var result = cut.buildEnv(Optional.of(config));

        // Then
        assertThat(result)
            .containsEntry("YEHAA", "oh oh_yeah-look_at_me ha")
            .containsEntry("dings", "bums");
    }
}
