package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mediamarktsaturn.technolinator.ConfigBuilder.build;
import static com.mediamarktsaturn.technolinator.ConfigBuilder.create;
import static com.mediamarktsaturn.technolinator.git.TechnolinatorConfig.JdkConfig;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CdxgenClientCommandCreationTest {

    @Inject
    CdxgenClient cut;

    @Test
    void testPureProject() {
        // Given
        var config = ConfigBuilder.create("pure").build();

        // When
        var paths = cut.buildConfigPaths(Optional.of(config));

        // Then
        assertThat(paths).hasSize(1)
            .element(0).satisfies(e0 -> {
                assertThat(e0).hasSize(1);
            });
    }

    @Test
    void testConfiglessProject() {
        // Given & When
        var paths = cut.buildConfigPaths(Optional.empty());

        // Then
        assertThat(paths).isEmpty();
    }

    @Test
    void testFlatListedProjects() {
        // Given
        var config = ConfigBuilder.create("root")
            .addSubProject(ConfigBuilder.create("sub1").build())
            .addSubProject(ConfigBuilder.create("sub2").build())
            .addSubProject(ConfigBuilder.create("sub3").build())
            .build();

        // When
        var paths = cut.buildConfigPaths(Optional.of(config));

        // Then
        assertThat(paths).hasSize(3)
            .allSatisfy(sub ->
                assertThat(sub).hasSize(2)
                    .element(0).satisfies(root ->
                        assertThat(root.project().name()).hasToString("root")
                    )
            );
        assertThat(paths.stream().map(p -> p.get(1).project().name()))
            .containsExactlyInAnyOrder("sub1", "sub2", "sub3");
    }

    @Test
    void testComplexTreeProject() {
        /*
            root
            |- sub1
            |- sub2
            |  |- sub21
            |  |- sub22
            |  |   |- sub221
            |  |   |- sub222
            |  |- sub23
            |- sub3
            |  |- sub31
            |  |- sub32
            |  |- sub33
            |     |- sub331
            |     |- sub332
            |     |- sub333
            |     |  |- sub3331
            |     |  |- sub3332
            |     |  |- sub3333
            |     |- sub334
            |- sub4

         */
        // Given
        var config = create("root")
            .addSubProject(build("sub1"))
            .addSubProject(create("sub2")
                .addSubProject(build("sub21"))
                .addSubProject(create("sub22")
                    .addSubProject(build("sub221"))
                    .addSubProject(build("sub222"))
                    .build())
                .addSubProject(build("sub23"))
                .build())
            .addSubProject(create("sub3")
                .addSubProject(build("sub31"))
                .addSubProject(build("sub32"))
                .addSubProject(create("sub33")
                    .addSubProject(build("sub331"))
                    .addSubProject(build("sub332"))
                    .addSubProject(create("sub333")
                        .addSubProject(build("sub3331"))
                        .addSubProject(build("sub3332"))
                        .addSubProject(build("sub3333"))
                        .build())
                    .addSubProject(build("sub334"))
                    .build())
                .build())
            .addSubProject(build("sub4"))
            .build();

        // When
        var paths = cut.buildConfigPaths(Optional.of(config));

        // Then
        assertThat(paths).hasSize(14);
        var index = new AtomicInteger(0);
        assertPath(paths, index.getAndIncrement(), "root", "sub1");
        assertPath(paths, index.getAndIncrement(), "root", "sub2", "sub21");
        assertPath(paths, index.getAndIncrement(), "root", "sub2", "sub22", "sub221");
        assertPath(paths, index.getAndIncrement(), "root", "sub2", "sub22", "sub222");
        assertPath(paths, index.getAndIncrement(), "root", "sub2", "sub23");
        assertPath(paths, index.getAndIncrement(), "root", "sub3", "sub31");
        assertPath(paths, index.getAndIncrement(), "root", "sub3", "sub32");
        assertPath(paths, index.getAndIncrement(), "root", "sub3", "sub33", "sub331");
        assertPath(paths, index.getAndIncrement(), "root", "sub3", "sub33", "sub332");
        assertPath(paths, index.getAndIncrement(), "root", "sub3", "sub33", "sub333", "sub3331");
        assertPath(paths, index.getAndIncrement(), "root", "sub3", "sub33", "sub333", "sub3332");
        assertPath(paths, index.getAndIncrement(), "root", "sub3", "sub33", "sub333", "sub3333");
        assertPath(paths, index.getAndIncrement(), "root", "sub3", "sub33", "sub334");
        assertPath(paths, index.getAndIncrement(), "root", "sub4");
    }

    @Test
    void testConfigSlicing() {
        // Given
        var path = List.of(
            ConfigBuilder.build("first!"),
            ConfigBuilder.create().enable(true).jdk(new JdkConfig("11")).build(),
            ConfigBuilder.create().jdk(new JdkConfig("11")).build(),
            ConfigBuilder.create().enable(false).jdk(new JdkConfig("20")).build(),
            ConfigBuilder.build("ignore me"),
            ConfigBuilder.create().enable(false).build(),
            ConfigBuilder.create().enable(true).jdk(new JdkConfig("17")).build(),
            ConfigBuilder.build("ignore me, too")
        );

        // When
        var enablement = cut.sliceConfig(path, TechnolinatorConfig::enable);
        var jdks = cut.sliceConfig(path, TechnolinatorConfig::jdk, JdkConfig::version);
        var env = cut.sliceConfig(path, TechnolinatorConfig::env);

        // Then
        assertThat(enablement).containsExactly(true, false, false, true);
        assertThat(jdks).containsExactly("11", "11", "20", "17");
        assertThat(env).isEmpty();
    }

    private void assertPath(List<List<TechnolinatorConfig>> paths, int index, String... list) {
        assertThat(paths).element(index).satisfies(path ->
            assertThat(unwrap(path)).containsExactly(list)
        );
    }

    private List<String> unwrap(List<TechnolinatorConfig> path) {
        return path.stream().map(p -> p.project().name()).toList();
    }
}
