package com.mediamarktsaturn.ghbot.sbom;

import static com.mediamarktsaturn.ghbot.TestUtil.await;

import java.io.File;
import java.util.Optional;

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
public class LocalRepositoryAnalysis {

    @Inject
    CdxgenClient cdxgenClient;

    final ObjectMapper configMapper = new ObjectMapper(new YAMLFactory());

    String dir = "/home/heubeck/w/sbom-test/fiscas-mono";

    @Language("yml")
    String configString = """
        analysis:
            recursive: false
        env:
            ORG_GRADLE_PROJECT_artifactoryUser: ${ARTIFACTORY_USER}
            ORG_GRADLE_PROJECT_artifactoryPassword: ${ARTIFACTORY_PASSWORD}
            ORG_GRADLE_PROJECT_artifactoryUrl: ${ARTIFACTORY_URL}
        """;

    @Test
    void runLocalAnalysis() throws Exception {
        var folder = new File(dir);
        if (!folder.exists() || !folder.canRead()) {
            throw new IllegalArgumentException("Cannot access " + dir);
        }
        var projectName = folder.getName();
        var metadata = new Command.Metadata("local", "local/" + projectName, "", Optional.empty());

        TechnolinatorConfig config = configMapper.readValue(configString, TechnolinatorConfig.class);

        var cmd = cdxgenClient.createCommand(folder, projectName, Optional.of(config));
        Log.infof("Command: '%s'", cmd.commandLine());

        var result = await(cmd.execute(metadata));

        Log.infof("Analysis success, got: %s", result.getClass().getSimpleName());
    }
}
