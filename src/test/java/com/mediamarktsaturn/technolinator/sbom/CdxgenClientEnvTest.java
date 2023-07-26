package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CdxgenClientEnvTest {

    @Inject
    CdxgenClient cut;

    @Test
    void testSingleEnvValueSubstitution() {
        // Given
        var value = "-PdingsTeil=${test_Env1}";

        // When
        var result = cut.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil=this's just a test");
    }

    @Test
    void testMultipleEnvValueSubstitutions() {
        // Given
        var value = "-PdingsTeil=${test_Env1} -Doh=${test_Env2} --yeah=${test_Env1}";

        // When
        var result = cut.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil=this's just a test -Doh=oh_yeah-look_at_me --yeah=this's just a test");
    }

    @Test
    void testNoneEnvValueSubstitutions() {
        // Given
        var value = "-PdingsTeil=${never_WILL_be_there} $ever ";

        // When
        var result = cut.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil=never_WILL_be_there $ever ");
    }

    @Test
    void testSingleGradleEnv() {
        // Given
        var config = ConfigBuilder.create().gradle(new TechnolinatorConfig.GradleConfig(
            List.of(
                "-PartifactoryUser=\"${test_Env1}\"",
                "-DartifactoryPassword=${test_Env2}",
                "-BgoAway=${withThis}"
            ))
        ).build();

        // When
        var result = cut.buildEnv(List.of(config), false);

        // Then
        assertThat(result)
            .containsEntry("GRADLE_ARGS", "-PartifactoryUser=\"this's just a test\" -DartifactoryPassword=oh_yeah-look_at_me -BgoAway=withThis");
    }

    @Test
    void testHierarchicalGradleEnv() {
        // Given
        var config1 = ConfigBuilder.create().gradle(new TechnolinatorConfig.GradleConfig(
            List.of(
                "-DartifactoryPassword=${test_Env2}",
                "-BgoAway=${withThis}"
            ))
        ).build();
        var config2 = ConfigBuilder.create().gradle(new TechnolinatorConfig.GradleConfig(
            List.of(
                "-PartifactoryUser=\"${test_Env1}\"",
                "-BgoAway=I'm last"
            ))
        ).build();

        // When
        var result = cut.buildEnv(List.of(config1, config2), false);

        // Then
        assertThat(result)
            .containsEntry("GRADLE_ARGS", "-DartifactoryPassword=oh_yeah-look_at_me -BgoAway=withThis -PartifactoryUser=\"this's just a test\" -BgoAway=I'm last");
    }

    @Test
    void testSingleMavenEnv() {
        // Given
        var config = ConfigBuilder.create().maven(new TechnolinatorConfig.MavenConfig(
            List.of(
                "-P\"${test_Env1}\"",
                "-DRUN=${test_Env2}",
                "-BgoAway=${withThis}"
            ))
        ).build();

        // When
        var result = cut.buildEnv(List.of(config), false);

        // Then
        assertThat(result)
            .containsEntry("MVN_ARGS", "-B -ntp -P\"this's just a test\" -DRUN=oh_yeah-look_at_me -BgoAway=withThis");
    }

    @Test
    void testHierarchicalMavenEnv() {
        // Given
        var config1 = ConfigBuilder.create().maven(new TechnolinatorConfig.MavenConfig(
            List.of(
                "-P\"${test_Env1}\"",
                "-DRUN=${test_Env2}"
            ))
        ).build();

        var config2 = ConfigBuilder.create().maven(new TechnolinatorConfig.MavenConfig(
            List.of(
                "-BgoAway=${withThis}"
            ))
        ).build();

        // When
        var result = cut.buildEnv(List.of(config1, config2), false);

        // Then
        assertThat(result)
            .containsEntry("MVN_ARGS", "-B -ntp -P\"this's just a test\" -DRUN=oh_yeah-look_at_me -BgoAway=withThis");
    }

    @Test
    void testMixedArgs() {
        // Given
        var config = ConfigBuilder.create().maven(new TechnolinatorConfig.MavenConfig(
                List.of("maven (${test_Env1})"))
            ).gradle(new TechnolinatorConfig.GradleConfig(
                List.of("gradle {${test_Env2}}"))
            ).env(Map.of("one", "ten"))
            .build();

        // When
        var result = cut.buildEnv(List.of(config), true);

        // Then
        assertThat(result)
            .containsEntry("one", "ten")
            .containsEntry("GRADLE_ARGS", "gradle {oh_yeah-look_at_me}")
            .containsEntry("FETCH_LICENSE", "true")
            .containsEntry("MVN_ARGS", "-B -ntp maven (this's just a test)");
    }

    @Test
    void testWithoutArgs() {
        // When
        var result = cut.buildEnv(List.of(), false);

        // Then
        assertThat(result)
            .doesNotContainKey("GRADLE_ARGS")
            .containsEntry("CDXGEN_TIMEOUT_MS", Long.toString(60 * 60 * 1000))
            .containsEntry("MVN_ARGS", "-B -ntp")
            .containsEntry("CDX_MAVEN_INCLUDE_TEST_SCOPE", "false")
            .containsEntry("FETCH_LICENSE", "false")
            .containsEntry("USE_GOSUM", "true")
            .containsEntry("JAVA_HOME", System.getenv("JAVA_HOME"));
    }

    @Test
    void testSingleJdkVersionSelection() {
        // Given
        var config = ConfigBuilder.create()
            .jdk(new TechnolinatorConfig.JdkConfig("20"))
            .build();

        // When
        var result = cut.buildEnv(List.of(config), false);

        // Then
        assertThat(result).containsEntry("JAVA_HOME", "/path/to/jdk20");
    }

    @Test
    void testOverlayJdkVersionSelection() {
        // Given
        var config1 = ConfigBuilder.create()
            .jdk(new TechnolinatorConfig.JdkConfig("20"))
            .build();

        var config2 = ConfigBuilder.create()
            .jdk(new TechnolinatorConfig.JdkConfig("17"))
            .build();

        // When
        var result = cut.buildEnv(List.of(config1, config2), false);

        // Then
        assertThat(result).containsEntry("JAVA_HOME", "/path/to/jdk17");
    }

    @Test
    void testSingleEnv() {
        // Given
        var config = ConfigBuilder.create()
            .env(Map.of("YEHAA", "oh ${test_Env2} ha", "dings", "bums"))
            .build();

        // When
        var result = cut.buildEnv(List.of(config), false);

        // Then
        assertThat(result)
            .containsEntry("YEHAA", "oh oh_yeah-look_at_me ha")
            .containsEntry("dings", "bums");
    }

    @Test
    void testHierarchicalEnv() {
        // Given
        var config1 = ConfigBuilder.create()
            .env(Map.of("YEHAA", "oh ${test_Env2} ha", "dings", "bums"))
            .build();

        var config2 = ConfigBuilder.create()
            .env(Map.of("YEHAA", "oh ${test_Env1} ha", "onemore", "thing"))
            .build();

        var config3 = ConfigBuilder.create()
            .env(Map.of("dings", "zapzarapp"))
            .build();

        // When
        var result = cut.buildEnv(List.of(config1, config2, config3), false);

        // Then
        assertThat(result)
            .containsEntry("YEHAA", "oh this's just a test ha")
            .containsEntry("dings", "zapzarapp")
            .containsEntry("onemore", "thing");
    }
}
