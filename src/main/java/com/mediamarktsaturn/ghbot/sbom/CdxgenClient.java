package com.mediamarktsaturn.ghbot.sbom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.parsers.JsonParser;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.mediamarktsaturn.ghbot.Command;
import com.mediamarktsaturn.ghbot.Result;
import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;
import com.mediamarktsaturn.ghbot.os.ProcessHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

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
    private static final String CDXGEN_GRADLE_MULTI_PROJECT = "GRADLE_MULTI_PROJECT_MODE";
    private static final String CDXGEN_MAVEN_ARGS = "MVN_ARGS";
    private static final String DEFAULT_MAVEN_ARGS = "-B -ntp";
    private static final String JAVA_HOME = System.getenv("JAVA_HOME");

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)}");

    private static final JsonParser SBOM_PARSER = new JsonParser();

    private static final List<String> WRAPPER_SCRIPT_NAMES = List.of("mvnw", "mvnw.bat", "mvnw.cmd", "gradlew", "gradlew.bat", "gradlew.cmd");

    private final Map<String, String> cdxgenEnv;

    /**
     * Configurable, supported jdk versions.
     */
    private final Map<String, String> jdkHomes;
    private final boolean cleanWrapperScripts, excludeGithubFolder, recursiveDefault, failOnError;

    public CdxgenClient(
        @ConfigProperty(name = "github.token")
        String githubToken,
        @ConfigProperty(name = "cdxgen.fetch_license")
        boolean fetchLicense,
        @ConfigProperty(name = "cdxgen.use_gosum")
        boolean useGosum,
        @ConfigProperty(name = "app.clean_wrapper_scripts")
        boolean cleanWrapperScripts,
        @ConfigProperty(name = "app.exclude_github_folder")
        boolean excludeGithubFolder,
        @ConfigProperty(name = "analysis.recursive_default")
        boolean recursiveDefault,
        @ConfigProperty(name = "cdxgen.fail_on_error")
        boolean failOnError
    ) {
        this.cleanWrapperScripts = cleanWrapperScripts;
        this.excludeGithubFolder = excludeGithubFolder;
        this.recursiveDefault = recursiveDefault;
        this.failOnError = failOnError;

        // https://github.com/AppThreat/cdxgen#environment-variables
        this.cdxgenEnv = Map.of(
            "GITHUB_TOKEN", githubToken.trim(),
            "FETCH_LICENSE", Boolean.toString(fetchLicense),
            "USE_GOSUM", Boolean.toString(useGosum),
            CDXGEN_MAVEN_ARGS, DEFAULT_MAVEN_ARGS,
            "CDXGEN_TIMEOUT_MS", Integer.toString(10 * 60 * 1000) // TODO: make configurable
        );

        jdkHomes = System.getenv().entrySet().stream()
            .filter(e -> e.getKey().matches("JAVA\\d+_HOME"))
            .collect(Collectors.toMap(
                e -> e.getKey().replace("JAVA", "").replace("_HOME", ""),
                Map.Entry::getValue
            ));
    }

    // TODO: DOKU!!!!!!
    private static final String CDXGEN_CMD_FMT = "cdxgen -o %s%s%s --project-name %s";

    public record SbomCreationCommand(
        Path repoDir,
        String projectName,
        String commandLine,
        Map<String, String> environment,
        List<String> excludeFiles
    ) implements Command<SBOMGenerationResult> {

        @Override
        public Uni<Result<SBOMGenerationResult>> execute(Metadata metadata) {
            metadata.writeToMDC();
            return removeExcludedFiles(this)
                .chain(() -> generateSbom(this, metadata))
                .chain(result -> parseSbom(this, result, metadata));
        }

    }

    // TODO: announce config errors to repo
    public SbomCreationCommand createCommand(Path repoDir, String projectName, Optional<TechnolinatorConfig> config) {
        boolean recursive =
            // recursive flag must not be set together with gradle multi project mode
            !config.map(TechnolinatorConfig::gradle).map(TechnolinatorConfig.GradleConfig::multiProject).orElse(false) &&
                config.map(TechnolinatorConfig::analysis).map(TechnolinatorConfig.AnalysisConfig::recursive).orElse(recursiveDefault);

        String cdxgenCmd = CDXGEN_CMD_FMT.formatted(
            SBOM_JSON,
            recursive ? RECURSIVE_FLAG : "",
            failOnError ? FAIL_ON_ERROR_FLAG : "",
            projectName
        );

        var environment = buildEnv(config);
        var excludeList = buildExcludeList(config);

        return new SbomCreationCommand(
            repoDir,
            projectName,
            cdxgenCmd,
            environment,
            excludeList
        );
    }

    static Uni<ProcessHandler.ProcessResult> generateSbom(SbomCreationCommand cmd, Command.Metadata metadata) {
        metadata.writeToMDC();
        return ProcessHandler.run(cmd.commandLine, cmd.repoDir(), cmd.environment());
    }

    static Uni<Result<SBOMGenerationResult>> parseSbom(SbomCreationCommand cmd, ProcessHandler.ProcessResult generationResult, Command.Metadata metadata) {
        var sbomFile = cmd.repoDir().resolve(SBOM_JSON);
        return Uni.createFrom().item(() -> {
            metadata.writeToMDC();
            return switch (generationResult) {
                case ProcessHandler.ProcessResult.Success s -> parseSbomFile(sbomFile);
                case ProcessHandler.ProcessResult.Failure f -> {
                    if (Files.exists(sbomFile)) {
                        Log.warnf(f.cause(), "cdxgen failed for project '%s', but sbom file was yet created, trying to parse it", cmd.projectName());
                        yield parseSbomFile(sbomFile);
                    } else {
                        Log.warnf(f.cause(), "cdxgen failed for project '%s': %s", cmd.projectName(), cmd.commandLine);
                        yield new Result.Failure<>(f.cause());
                    }
                }
            };
        });
    }


    static Result<SBOMGenerationResult> parseSbomFile(Path sbomFile) {
        if (!Files.exists(sbomFile)) {
            return new Result.Success<>(new SBOMGenerationResult.None());
        } else if (Files.isReadable(sbomFile)) {
            try {
                return parseSbomBytes(Files.readAllBytes(sbomFile));
            } catch (Exception e) {
                return new Result.Failure<>(e);
            }
        } else {
            throw new IllegalStateException("Cannot read file " + sbomFile.toAbsolutePath());
        }
    }

    private static Result<SBOMGenerationResult> parseSbomBytes(byte[] sbomContent) {
        try {
            final var validationResult = SBOM_PARSER.validate(sbomContent);

            final var sbom = SBOM_PARSER.parse(sbomContent);

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
                return new Result.Success<>(new SBOMGenerationResult.None());
            } else if (named) {
                return new Result.Success<>(new SBOMGenerationResult.Proper(sbom, group, name, version, validationResult));
            } else {
                return new Result.Success<>(new SBOMGenerationResult.Fallback(sbom, validationResult));
            }
        } catch (Exception e) {
            return new Result.Failure<>(e);
        }
    }

    static boolean isEmpty(Collection<?> value) {
        return value == null || value.isEmpty();
    }

    static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }


    Map<String, String> buildEnv(Optional<TechnolinatorConfig> config) {
        var gradleEnv = config.map(TechnolinatorConfig::gradle).map(TechnolinatorConfig.GradleConfig::args).orElseGet(List::of);
        var gradleMultiProject = config.map(TechnolinatorConfig::gradle).map(TechnolinatorConfig.GradleConfig::multiProject).orElse(false);
        var mavenEnv = config.map(TechnolinatorConfig::maven).map(TechnolinatorConfig.MavenConfig::args).orElseGet(List::of);
        var env = config.map(TechnolinatorConfig::env).orElseGet(Map::of);
        var jdkHome = config.map(TechnolinatorConfig::jdk).map(TechnolinatorConfig.JdkConfig::version).map(jdkHomes::get).orElse(JAVA_HOME);

        var context = new HashMap<>(cdxgenEnv);
        context.put("JAVA_HOME", jdkHome);

        var gradleEnvValue = gradleEnv.stream().map(CdxgenClient::resolveEnvVars).collect(Collectors.joining(" "));
        if (!gradleEnvValue.isBlank()) {
            context.put(CDXGEN_GRADLE_ARGS, gradleEnvValue);
        }
        if (gradleMultiProject) {
            context.put(CDXGEN_GRADLE_MULTI_PROJECT, "true");
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

    List<String> buildExcludeList(Optional<TechnolinatorConfig> config) {
        var repoConfigExcludes = config.map(TechnolinatorConfig::analysis).map(TechnolinatorConfig.AnalysisConfig::excludes).orElseGet(List::of);
        if (repoConfigExcludes.stream().anyMatch(item -> item.contains("..") || item.trim().startsWith("/") || item.trim().startsWith("~") || item.trim().startsWith("$"))) {
            throw new IllegalArgumentException("Not allowed to step up directories");
        }

        var excludeList = new ArrayList<>(repoConfigExcludes);

        if (excludeGithubFolder) {
            excludeList.add(".github");
        }
        if (cleanWrapperScripts) {
            excludeList.addAll(WRAPPER_SCRIPT_NAMES);
        }

        return excludeList;
    }

    /**
     * Replaces variable templates in form ${VAR} with the actual env value
     * // TODO: access secrets from repo secrets instead of env?
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

    static Uni<Void> removeExcludedFiles(SbomCreationCommand cmd) {
        if (cmd.excludeFiles().isEmpty()) {
            return Uni.createFrom().voidItem();
        } else {
            var dir = cmd.repoDir();
            var excludes = String.join(" ", cmd.excludeFiles());
            // TODO: preserve root | use limited user rights
            return ProcessHandler.run("rm -rf " + excludes, dir, Map.of())
                .onItem().ignore().andContinueWithNull();
        }
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
    }

}
