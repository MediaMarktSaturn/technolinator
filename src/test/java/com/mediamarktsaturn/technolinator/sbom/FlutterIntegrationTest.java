package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.ConfigBuilder;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@EnabledIfEnvironmentVariable(named = "FLUTTER_INTEGRATION_PROJECT", matches = ".+")
class FlutterIntegrationTest {

    @Inject
    CdxgenClient client;

    @Test
    void includesPubAndAndroidDependencies() {
        var directory = Path.of(System.getenv("FLUTTER_INTEGRATION_PROJECT"));
        var projectName = System.getenv().getOrDefault("FLUTTER_INTEGRATION_NAME", "flutter-example");
        var version = System.getenv().getOrDefault("FLUTTER_INTEGRATION_VERSION", "3.44.1");
        var config = ConfigBuilder.create()
            .flutter(new TechnolinatorConfig.FlutterConfig(version, null))
            .jdk(new TechnolinatorConfig.JdkConfig("17"))
            .build();
        var command = client.createCommands(directory, projectName, false, Optional.of(config)).getFirst();
        var metadata = new Command.Metadata("local", "local/example", "", Optional.empty());

        assertThat(await(command.execute(metadata))).isInstanceOfSatisfying(Result.Success.class, success ->
            assertThat(success.result()).isInstanceOfSatisfying(CdxgenClient.SBOMGenerationResult.Yield.class, yield -> {
                var purls = yield.sbom().getComponents().stream().map(Component::getPurl).filter(Objects::nonNull).toList();
                assertThat(purls.stream().filter(purl -> purl.startsWith("pkg:pub/")).count()).isGreaterThan(1);
                assertThat(purls).anyMatch(purl -> purl.startsWith("pkg:maven/androidx.")
                    || purl.startsWith("pkg:maven/com.android"));
            }));
    }
}
