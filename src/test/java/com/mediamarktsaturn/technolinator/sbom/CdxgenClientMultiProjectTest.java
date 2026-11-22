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
    void serializesFlutterAnalysesSharingACheckout(@TempDir Path temp) throws IOException {
        var root = Files.createDirectory(temp.resolve("repo"));
        Files.writeString(root.resolve("pubspec.yaml"), "name: example\n");
        var android = Files.createDirectory(root.resolve("android"));
        var properties = android.resolve("local.properties");
        Files.writeString(properties, "existing=value\n");
        var sdk = Files.createDirectories(temp.resolve("sdks/flutter/3.44.1/bin"));
        var flutter = Files.writeString(sdk.resolve("flutter"), "#!/bin/sh\nexit 0\n");
        assertThat(flutter.toFile().setExecutable(true)).isTrue();
        var androidSdk = Files.createDirectory(temp.resolve("android-sdk"));
        var cdxgen = Files.writeString(temp.resolve("fake-cdxgen"), """
            #!/bin/sh
            grep -Fq "$FLUTTER_ROOT" android/local.properties || exit 1
            sleep 1
            grep -Fq "$FLUTTER_ROOT" android/local.properties || exit 1
            """);
        assertThat(cdxgen.toFile().setExecutable(true)).isTrue();
        var env = Map.of("SDK_DIR", temp.resolve("sdks").toString(), "ANDROID_HOME", androidSdk.toString());
        var config = new TechnolinatorConfig.FlutterConfig("3.44.1", null);
        var first = new CdxgenClient.SbomCreationCommand(root, "first", cdxgen.toString(), env, List.of(), "", config);
        var second = new CdxgenClient.SbomCreationCommand(root, "second", cdxgen.toString(), env, List.of(), "", config);
        var metadata = new Command.Metadata("local", "local/example", "", Optional.empty());

        assertThat(await(executeCommands(List.of(first, second), metadata)))
            .hasSize(2).allMatch(result -> result instanceof Result.Success<?>);
        assertThat(properties).hasContent("existing=value\n");
    }
}
