package com.mediamarktsaturn.technolinator.sbom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkiverse.githubapp.runtime.UtilsProducer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static com.mediamarktsaturn.technolinator.handler.AnalysisProcessHandler.executeCommands;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class CdxgenClientMultiProjectTest {

    @Inject
    CdxgenClient cut;

    final ObjectMapper configMapper = new UtilsProducer().yamlObjectMapper();

    @Test
    void testMultiProjectCommandGeneration() throws IOException {
        // Given
        var config = configMapper.readValue(getClass().getResourceAsStream("/configs/multi-project-repo.yml"), TechnolinatorConfig.class);
        var path = Paths.get("src/test/resources/repo/multi-project-repo");

        // When
        var cmds = cut.createCommands(path, "multi", false, Optional.of(config));

        // Then
        assertThat(cmds).hasSize(3);
        var sub11 = cmds.stream().filter(c -> c.projectName().equals("multi-sub1-sub11")).findFirst().get();
        var sub12 = cmds.stream().filter(c -> c.projectName().equals("multi-sub12")).findFirst().get();
        var sub2 = cmds.stream().filter(c -> c.projectName().equals("multi-sub2")).findFirst().get();

        assertThat(sub11.repoDir()).hasToString("src/test/resources/repo/multi-project-repo/sub1/sub11");
        assertThat(sub12.repoDir()).hasToString("src/test/resources/repo/multi-project-repo/sub1/sub12");
        assertThat(sub2.repoDir()).hasToString("src/test/resources/repo/multi-project-repo/sub2");

        // Given even more
        var metadata = new Command.Metadata("https://github.com/SomeOrg/SomeRepo", "SomeOrg/SomeRepo", "0815", Optional.empty());

        // When again
        var results = await(executeCommands(cmds, metadata));

        // Then eventually
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(r -> r instanceof Result.Success<CdxgenClient.SBOMGenerationResult>);
    }

    @Test
    void serializesAnalysesOfProjectsWithPrepareScripts(@TempDir Path temp) throws IOException {
        var root = Files.createDirectory(temp.resolve("repo"));
        // each analysis marks the checkout while it's running, and fails if it finds the mark of the other one
        var script = new TechnolinatorConfig.PrepareConfig("""
            if [ -e busy ]; then echo overlapping; exit 1; fi
            touch busy
            sleep 1
            """);
        var cdxgen = Files.writeString(temp.resolve("fake-cdxgen"), """
            #!/bin/sh
            [ -e busy ] || exit 1
            rm busy
            """);
        assertThat(cdxgen.toFile().setExecutable(true)).isTrue();
        var first = new CdxgenClient.SbomCreationCommand(root, "first", cdxgen.toString(), Map.of("PATH", "/usr/bin:/bin"), List.of(), "", script);
        var second = new CdxgenClient.SbomCreationCommand(root, "second", cdxgen.toString(), Map.of("PATH", "/usr/bin:/bin"), List.of(), "", script);
        var metadata = new Command.Metadata("local", "local/example", "", Optional.empty());

        assertThat(await(executeCommands(List.of(first, second), metadata)))
            .hasSize(2).allMatch(result -> result instanceof Result.Success<?>);
    }
}
