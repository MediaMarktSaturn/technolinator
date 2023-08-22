package com.mediamarktsaturn.technolinator.sbom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkiverse.githubapp.runtime.UtilsProducer;
import io.quarkus.logging.Log;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.fail;

// to be used for manual, local testing only
@Disabled
@QuarkusTest
class LocalRepositoryAnalysis {

    @Inject
    CdxgenClient cdxgenClient;

    @Inject
    SbomqsClient sbomqsClient;

    final ObjectMapper configMapper = new UtilsProducer().yamlObjectMapper();

    String dir = "/home/heubeck/w/sbom-test/store-stock-service";

    @Language("yml")
    String configString = """
        ---
        analysis:
          recursive: true
        #gradle:
        #  args:
        #     - -PartifactoryUser=${ARTIFACTORY_USER}
        #     - -PartifactoryPassword=${ARTIFACTORY_PASSWORD}
        #     - -PartifactoryUrl=${ARTIFACTORY_URL}
        #     - -PgithubToken=${GITHUB_TOKEN}
        #     - -PgithubUser=${GITHUB_USER}
        #     - -PgcpProjectId=nowhere
        #     - -PgithubSHA=none
        #jdk:
        #  version: 17
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

        var cmds = cdxgenClient.createCommands(folder, projectName.toString(), false, Optional.of(config));

        cmds.forEach(cmd -> {
            Log.infof("Command: '%s'", cmd.commandLine());
            Log.infof("Env: %n%s", cmd.environment().entrySet().stream().map(e -> "%s=%s".formatted(e.getKey(), e.getValue())).collect(Collectors.joining(System.lineSeparator())));
            var result = await(cmd.execute(metadata));

            switch (result) {
                case Result.Success<CdxgenClient.SBOMGenerationResult> s -> {
                    Log.infof("Success: %s", s.result().getClass().getSimpleName());
                    if (s.result() instanceof CdxgenClient.SBOMGenerationResult.Yield y) {
                        var scoreResult = await(sbomqsClient.calculateQualityScore(y.sbomFile()));
                        switch (scoreResult) {
                            case Result.Success<SbomqsClient.QualityScore> score ->
                                Log.infof("Score: %s", score.result().score());
                            case Result.Failure<SbomqsClient.QualityScore> fail -> fail("Scoring failed", fail.cause());
                        }
                    }
                }
                case Result.Failure<CdxgenClient.SBOMGenerationResult> f -> fail("Failed", f.cause());
            }
        });
    }
}
