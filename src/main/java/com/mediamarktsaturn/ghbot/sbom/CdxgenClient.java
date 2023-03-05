package com.mediamarktsaturn.ghbot.sbom;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.enterprise.context.ApplicationScoped;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.parsers.JsonParser;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import com.mediamarktsaturn.ghbot.os.ProcessHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
public class CdxgenClient {

    private static final String SBOM_JSON = "sbom.json";

    /**
     * cdxgen option -r: Recurse mode suitable for mono-repos
     * Used for projects containing multiple dependency files like pom.xml & yarn.lock
     */
    private static final String RECURSIVE_FLAG = " -r";
    private static final String FAIL_ON_ERROR_FLAG = " --fail-on-error";

    private static final String CDXGEN_GRADLE_ARGS = "GRADLE_ARGS";
    private static final String CDXGEN_MAVEN_ARGS = "MVN_ARGS";
    private static final String DEFAULT_MAVEN_ARGS = "-B -ntp";

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)}");

    private static final List<String> WRAPPER_SCRIPT_NAMES = List.of("mvnw", "mvnw.bat", "mvnw.cmd", "gradlew", "gradlew.bat", "gradlew.cmd");

    private final Map<String, String> cdxgenEnv;
    private final boolean cleanWrapperScripts, recursiveDefault, failOnError;

    public CdxgenClient(
        @ConfigProperty(name = "github.token")
        String githubToken,
        @ConfigProperty(name = "cdxgen.fetch_license")
        boolean fetchLicense,
        @ConfigProperty(name = "cdxgen.use_gosum")
        boolean useGosum,
        @ConfigProperty(name = "app.clean_wrapper_scripts")
        boolean cleanWrapperScripts,
        @ConfigProperty(name = "analysis.recursive_default")
        boolean recursiveDefault,
        @ConfigProperty(name = "cdxgen.fail_on_error")
        boolean failOnError
    ) {
        this.cleanWrapperScripts = cleanWrapperScripts;
        this.recursiveDefault = recursiveDefault;
        this.failOnError = failOnError;

        // https://github.com/AppThreat/cdxgen#environment-variables
        this.cdxgenEnv = Map.of(
            "GITHUB_TOKEN", githubToken.trim(),
            "FETCH_LICENSE", Boolean.toString(fetchLicense),
            "USE_GOSUM", Boolean.toString(useGosum),
            "MVN_ARGS", DEFAULT_MAVEN_ARGS,
            "CDXGEN_TIMEOUT_MS", Integer.toString(10 * 60 * 1000)
        );
    }

    private static final String CDXGEN_CMD_FMT = "cdxgen -o %s%s%s --project-name %s";

    public Uni<SBOMGenerationResult> generateSBOM(File repoDir, String projectName, Optional<TechnolinatorConfig> config) {
        String cdxgenCmd = CDXGEN_CMD_FMT.formatted(
            SBOM_JSON,
            config.map(TechnolinatorConfig::analysis).map(TechnolinatorConfig.AnalysisConfig::recursive).orElse(recursiveDefault) ? RECURSIVE_FLAG : "",
            failOnError ? FAIL_ON_ERROR_FLAG : "",
            projectName
        );

        Function<ProcessHandler.ProcessResult, SBOMGenerationResult> mapResult = (ProcessHandler.ProcessResult processResult) -> {
            var sbomFile = new File(repoDir, SBOM_JSON);
            if (processResult instanceof ProcessHandler.ProcessResult.Success) {
                return readAndParseSBOM(sbomFile);
            } else {
                var failure = (ProcessHandler.ProcessResult.Failure) processResult;
                if (sbomFile.exists()) {
                    Log.warnf(failure.cause(), "cdxgen failed for project '%s', but sbom file was yet created, trying to parse it", projectName);
                    return readAndParseSBOM(sbomFile);
                } else {
                    return new SBOMGenerationResult.Failure("Command failed: " + cdxgenCmd, failure.cause());
                }
            }
        };

        return prepareForAnalysis(repoDir.getAbsoluteFile(), config)
            .chain(dir -> ProcessHandler.run(cdxgenCmd, dir, buildEnv(config)))
            .map(mapResult);
    }

    Map<String, String> buildEnv(Optional<TechnolinatorConfig> config) {
        var gradleEnv = config.map(TechnolinatorConfig::gradle).map(TechnolinatorConfig.GradleConfig::args).orElseGet(List::of);
        var mavenEnv = config.map(TechnolinatorConfig::maven).map(TechnolinatorConfig.MavenConfig::args).orElseGet(List::of);
        var env = config.map(TechnolinatorConfig::env).orElseGet(Map::of);

        if (gradleEnv.isEmpty() && mavenEnv.isEmpty() && env.isEmpty()) {
            return cdxgenEnv;
        }

        var context = new HashMap<>(cdxgenEnv);
        var gradleEnvValue = gradleEnv.stream().map(CdxgenClient::resolveEnvVars).collect(Collectors.joining(" "));
        if (!gradleEnvValue.isBlank()) {
            context.put(CDXGEN_GRADLE_ARGS, gradleEnvValue);
        }
        var mavenEnvValue = mavenEnv.stream().map(CdxgenClient::resolveEnvVars).collect(Collectors.joining(" "));
        if (!mavenEnvValue.isBlank()) {
            context.put(CDXGEN_MAVEN_ARGS, DEFAULT_MAVEN_ARGS + " " + mavenEnvValue);
        }
        context.putAll(
            env.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> resolveEnvVars(e.getValue())
                )
            )
        );

        return context;
    }

    /**
     * Replaces variable templates in form ${VAR} with the actual env value
     */
    static String resolveEnvVars(String value) {
        var matcher = ENV_VAR_PATTERN.matcher(value);
        while (matcher.find()) {
            var envVar = matcher.group(1);
            var envVal = System.getenv(envVar);
            value = value.replace("${" + envVar + "}", envVal == null ? "" : envVal);
        }
        return value;
    }

    static SBOMGenerationResult readAndParseSBOM(File sbomFile) {
        if (!sbomFile.exists()) {
            return new SBOMGenerationResult.None();
        } else if (sbomFile.canRead()) {
            try {
                return parseSBOM(Files.readAllBytes(sbomFile.toPath()));
            } catch (Exception e) {
                return new SBOMGenerationResult.Failure("Failed to parse " + sbomFile.getAbsolutePath(), e);
            }
        } else {
            return new SBOMGenerationResult.Failure("Cannot read file " + sbomFile.getAbsolutePath(), null);
        }
    }

    private static SBOMGenerationResult parseSBOM(byte[] sbomContent) {
        try {
            final var jsonSBOMParser = new JsonParser();
            final var validationResult = jsonSBOMParser.validate(sbomContent);

            final var sbom = jsonSBOMParser.parse(sbomContent);

            String group = null;
            String name = null;
            String version = null;
            if (sbom.getMetadata() != null && sbom.getMetadata().getComponent() != null) {
                group = sbom.getMetadata().getComponent().getGroup();
                name = sbom.getMetadata().getComponent().getName();
                version = sbom.getMetadata().getComponent().getVersion();
            }
            var named = isNotBlank(group) && isNotBlank(name) && isNotBlank(version);
            if (!named &&
                isEmpty(sbom.getComponents()) && isEmpty(sbom.getDependencies()) && isEmpty(sbom.getServices())) {
                return new SBOMGenerationResult.None();
            } else if (named) {
                return new SBOMGenerationResult.Proper(sbom, group, name, version, validationResult);
            } else {
                return new SBOMGenerationResult.Fallback(sbom, validationResult);
            }
        } catch (ParseException parseException) {
            return new SBOMGenerationResult.Failure("SBOM file is invalid", parseException);
        } catch (IOException ioException) {
            return new SBOMGenerationResult.Failure("SBOM file could not be read", ioException);
        }
    }

    static boolean isEmpty(Collection<?> value) {
        return value == null || value.isEmpty();
    }

    static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    Uni<File> prepareForAnalysis(File dir, Optional<TechnolinatorConfig> config) {
        var excludeList = config.map(TechnolinatorConfig::analysis).map(TechnolinatorConfig.AnalysisConfig::excludes).orElseGet(List::of);
        if (excludeList.stream().anyMatch(item -> item.contains("..") || item.trim().startsWith("/") || item.trim().startsWith("~"))) {
            throw new IllegalArgumentException("Not allowed to step up directories");
        }
        String excludes = String.join(" ", excludeList);

        String toBeDeleted = excludes + " .github ";
        if (cleanWrapperScripts) {
            toBeDeleted += String.join(" ", WRAPPER_SCRIPT_NAMES);
        }
        return ProcessHandler.run("rm -rf " + toBeDeleted, dir, Map.of())
            .map(i -> dir);
    }

    public sealed interface SBOMGenerationResult {
        record Proper(
            Bom sbom,
            String group,
            String name,
            String version,
            List<ParseException> validationIssues
        ) implements SBOMGenerationResult {
        }

        record Fallback(
            Bom sbom,
            List<ParseException> validationIssues
        ) implements SBOMGenerationResult {
        }

        record None() implements SBOMGenerationResult {
        }

        record Failure(
            String message,
            Throwable cause
        ) implements SBOMGenerationResult {
        }
    }

}
