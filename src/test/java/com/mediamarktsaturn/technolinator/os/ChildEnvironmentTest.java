package com.mediamarktsaturn.technolinator.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChildEnvironmentTest {

    @Test
    void inheritsToolingButNoSecrets() {
        // Given
        var parent = Map.of(
            "PATH", "/usr/bin",
            "HOME", "/data",
            "LC_ALL", "en_US.UTF-8",
            "JAVA21_HOME", "/sdk/java21",
            "JAVA_HOME", "/sdk/java25",
            "HTTPS_PROXY", "http://proxy",
            "CDXGEN_SERVER_URL", "http://cdxgen",
            "GITHUB_TOKEN", "secret",
            "GITHUB_USER", "nobody",
            "QUARKUS_GITHUB_APP_PRIVATE_KEY", "secret"
        );

        // When
        var result = ChildEnvironment.inherited(parent, List.of());

        // Then
        assertThat(result).containsOnlyKeys("PATH", "HOME", "LC_ALL", "JAVA21_HOME", "JAVA_HOME", "HTTPS_PROXY", "CDXGEN_SERVER_URL");
    }

    @Test
    void withoutSecretsDropsSecretLookingAndNamedVariables() {
        var env = Map.of(
            "PATH", "/usr/bin",
            "GITHUB_TOKEN", "a",
            "dtrack_apikey", "b",
            "MY_PASSWORD", "c",
            "CUSTOM", "d",
            "KEEP", "e");

        var result = ChildEnvironment.withoutSecrets(env, List.of("CUSTOM"));

        assertThat(result).containsOnlyKeys("PATH", "KEEP");
    }

    @Test
    void inheritsAdditionalNamesAndPrefixes() {
        // Given
        var parent = Map.of(
            "PATH", "/usr/bin",
            "GITHUB_USER", "nobody",
            "ARTIFACTORY_URL", "https://artifactory",
            "ARTIFACTORY_USER", "svc",
            "DTRACK_APIKEY", "secret"
        );

        // When
        var result = ChildEnvironment.inherited(parent, List.of("GITHUB_USER", "ARTIFACTORY_*"));

        // Then
        assertThat(result).containsOnlyKeys("PATH", "GITHUB_USER", "ARTIFACTORY_URL", "ARTIFACTORY_USER");
    }

    @Test
    void doesNotInheritCdxgenDebugModeUnlessRequested() {
        // Given
        var parent = Map.of("CDXGEN_DEBUG_MODE", "debug", "CDXGEN_SERVER_URL", "http://cdxgen");

        // When / Then
        assertThat(ChildEnvironment.inherited(parent, List.of())).containsOnlyKeys("CDXGEN_SERVER_URL");
        assertThat(ChildEnvironment.inherited(parent, List.of("CDXGEN_DEBUG_MODE"))).containsOnlyKeys("CDXGEN_SERVER_URL", "CDXGEN_DEBUG_MODE");
    }

    @Test
    void returnsMutableCopy() {
        var result = ChildEnvironment.inherited(Map.of("PATH", "/usr/bin"), List.of());

        result.put("X", "y");

        assertThat(result).containsEntry("X", "y");
    }

    @ParameterizedTest
    @CsvSource({
        "PATH, PATH, true",
        "PATH, PATHS, false",
        "PATH, XPATH, false",
        "LC_ALL, LC_*, true",
        "LC_, LC_*, true",
        "LCX, LC_*, false",
        "JAVA21_HOME, JAVA*_HOME, true",
        "JAVA_HOME, JAVA*_HOME, true",
        "JAVA_HOMEX, JAVA*_HOME, false",
        "JAVA_HOM, JAVA*_HOME, false",
        "JAVAHOME, JAVA*_HOME, false",
        "A_B_C, A*B*C, true",
        "A_C, A*B*C, false",
        "ABC, '*', true",
        "ABC, '', false",
    })
    void matchesPatterns(String name, String pattern, boolean expected) {
        assertThat(ChildEnvironment.matches(name, pattern)).isEqualTo(expected);
    }
}
