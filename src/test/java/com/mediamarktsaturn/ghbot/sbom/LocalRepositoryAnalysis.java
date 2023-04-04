package com.mediamarktsaturn.ghbot.sbom;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

import com.mediamarktsaturn.ghbot.Result;
import jakarta.inject.Inject;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.mediamarktsaturn.ghbot.Command;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;

// to be used for manual, local testing only
@Disabled
@QuarkusTest
class LocalRepositoryAnalysis {

    @Inject
    CdxgenClient cdxgenClient;

    final ObjectMapper configMapper = new ObjectMapper(new YAMLFactory());

    String dir = "/home/heubeck/w/sbom-test/ccr-customers";

    @Language("yml")
    String configString = """
---
gradle:
  args:
    - -PmavenUser=${ARTIFACTORY_USER}
    - -PmavenPassword=${ARTIFACTORY_PASSWORD}
jdk:
  version: 17
        """;

    @Test
    void runLocalAnalysis() throws Exception {
        var folder = Paths.get(dir);
        if (!Files.exists(folder) || !Files.isReadable(folder)) {
            throw new IllegalArgumentException("Cannot access " + dir);
        }
        var projectName = folder.getFileName();
        var metadata = new Command.Metadata("local", "local/" + projectName, "", Optional.empty());

        TechnolinatorConfig config = configMapper.readValue(configString, TechnolinatorConfig.class);

        var cmd = cdxgenClient.createCommand(folder, projectName.toString(), Optional.of(config));
        Log.infof("Command: '%s'", cmd.commandLine());

        var result = await(cmd.execute(metadata));

        switch (result) {
            case Result.Success<CdxgenClient.SBOMGenerationResult> s -> System.out.println("Success");
            case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> fail("Failed", f.cause());
        }
    }
}
