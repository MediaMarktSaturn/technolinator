package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredProjectsTest {

    private static TechnolinatorConfig node(String name, TechnolinatorConfig... children) {
        return new TechnolinatorConfig(null, null, name == null ? null : new TechnolinatorConfig.ProjectConfig(name), null, null, null, null, null,
            children.length == 0 ? null : List.of(children), null);
    }

    @Test
    void repositoryIsTheOnlyProjectWithoutConfiguration() {
        assertThat(ConfiguredProjects.projectNames("app", Optional.empty())).containsExactly("app");
    }

    @Test
    void repositoryIsTheOnlyProjectWithoutSubProjects() {
        assertThat(ConfiguredProjects.projectNames("app", Optional.of(node(null)))).containsExactly("app");
    }

    @Test
    void namesOfAllLeavesOfTheHierarchy() {
        // Given
        var config = node(null,
            node("api"),
            node("services", node("billing"), node("shipping")),
            node("white space"));

        // When
        var names = ConfiguredProjects.projectNames("app", Optional.of(config));

        // Then
        assertThat(names).containsExactlyInAnyOrder("app-api", "app-services-billing", "app-services-shipping", "app-white-space");
    }

    @Test
    void namesIncludeAllLevelsOfTheHierarchy() {
        // Given
        var config = node("root", node("first"), node("second"));

        // When
        var names = ConfiguredProjects.projectNames("app", Optional.of(config));

        // Then
        assertThat(names).containsExactlyInAnyOrder("app-root-first", "app-root-second");
    }
}
