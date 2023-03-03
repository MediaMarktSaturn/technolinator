package com.mediamarktsaturn.ghbot.sbom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;

import com.mediamarktsaturn.ghbot.ConfigBuilder;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class CdxgenClientEnvTest {

    @Inject
    CdxgenClient cut;

    @Test
    public void testSingleEnvValueSubstitution() {
        // Given
        var value = "-PdingsTeil=${test_Env1}";

        // When
        var result = CdxgenClient.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil=this's just a test");
    }

    @Test
    public void testMultipleEnvValueSubstitutions() {
        // Given
        var value = "-PdingsTeil=${test_Env1} -Doh=${test_Env2} --yeah=${test_Env1}";

        // When
        var result = CdxgenClient.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil=this's just a test -Doh=oh_yeah-look_at_me --yeah=this's just a test");
    }

    @Test
    public void testNoneEnvValueSubstitutions() {
        // Given
        var value = "-PdingsTeil=${never_WILL_be_there} $ever ";

        // When
        var result = CdxgenClient.resolveEnvVars(value);

        // Then
        assertThat(result).isEqualTo("-PdingsTeil= $ever ");
    }

    @Test
    public void testGradleEnv() {
        // Given
        var config = ConfigBuilder.create().gradle(new TechnolinatorConfig.GradleConfig(
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
    public void testMavenEnv() {
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
    public void testMixedArgs() {
        // Given
        var config = ConfigBuilder.create().maven(new TechnolinatorConfig.MavenConfig(
            List.of("maven (${test_Env1})"))
        ).gradle(new TechnolinatorConfig.GradleConfig(
            List.of("gradle {${test_Env2}}")
        )).build();

        // When
        var result = cut.buildEnv(Optional.of(config));

        // Then
        assertThat(result)
            .containsEntry("GRADLE_ARGS", "gradle {oh_yeah-look_at_me}")
            .containsEntry("MVN_ARGS", "-B -ntp maven (this's just a test)");
    }

    @Test
    public void testWithoutArgs() {
        // When
        var result = cut.buildEnv(Optional.empty());

        // Then
        assertThat(result)
            .doesNotContainKey("GRADLE_ARGS")
            .containsEntry("MVN_ARGS", "-B -ntp");
    }
}
