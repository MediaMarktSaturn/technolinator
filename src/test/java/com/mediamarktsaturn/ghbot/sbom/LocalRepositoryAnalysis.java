package com.mediamarktsaturn.ghbot.sbom;

import java.io.File;
import java.util.Optional;

import javax.inject.Inject;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

    String dir = "/home/heubeck/w/sbom-test/gly-points-correction-backend";

    @Language("yml")
    String configString = """
        analysis:
            recursive: false
        gradle:
          args:
            - -PartifactoryUser=${ARTIFACTORY_USER}
            - -PartifactoryPassword=${ARTIFACTORY_PASSWORD}
            - -PartifactoryUrl=${ARTIFACTORY_URL}
        """;

    @Test
    public void runLocalAnalysis() throws Exception {
        var folder = new File(dir);
        if (!folder.exists() || !folder.canRead()) {
            throw new IllegalArgumentException("Cannot access " + dir);
        }
        var projectName = folder.getName();

        TechnolinatorConfig config = configMapper.readValue(configString, TechnolinatorConfig.class);

        var result = cdxgenClient.generateSBOM(folder, projectName, Optional.of(config))
            .await().indefinitely();
        Log.infof("Analysis success, got: %s", result.getClass().getSimpleName());
    }
}