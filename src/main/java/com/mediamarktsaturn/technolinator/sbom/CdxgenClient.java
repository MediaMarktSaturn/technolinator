package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.parsers.JsonParser;
import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wraps around 'cdxgen' for SBOM creation
 */
@ApplicationScoped
public class CdxgenClient {

    private static final String SBOM_JSON = "sbom.json";

    /**
     * cdxgen option -r: Recurse mode suitable for mono-repos
     * Used for projects containing multiple dependency files like pom.xml & yarn.lock
     */
    private static final String RECURSIVE_FLAG = " -r";
    private static final String NO_RECURSIVE_FLAG = " --no-recurse";

    /**
     * cdxgen option --fail-on-error: Command exists with != 0 in case of any issues during analysis
     */
    private static final String FAIL_ON_ERROR_FLAG = " --fail-on-error";

    // cdxgen ENV variable names
    private static final String CDXGEN_GRADLE_ARGS = "GRADLE_ARGS";
    private static final String CDXGEN_MAVEN_ARGS = "MVN_ARGS";
    private static final String CDXGEN_FETCH_LICENSE = "FETCH_LICENSE";
    private static final String JAVA_HOME = System.getenv("JAVA_HOME");

    /**
     * Default arguments for a maven call, suppressing download progress output
     */
    private static final String DEFAULT_MAVEN_ARGS = "-B -ntp";

    /**
     * ENV variables to be replaced look like: ${my_ENV}
     */
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)}");

    private static final JsonParser SBOM_PARSER = new JsonParser();

    /**
     * Known wrapper script names that will be removed if configured by `app.clean_wrapper_scripts` property
     */
    private static final List<String> WRAPPER_SCRIPT_NAMES = List.of("mvnw", "mvnw.bat", "mvnw.cmd", "gradlew", "gradlew.bat", "gradlew.cmd");

    /**
     * Default ENV for cdxgen
     */
    private final Map<String, String> cdxgenEnv;

    /**
     * Variable names that are allowed to be resolved from ENV.
     */
    private final List<String> allowedEnvSubstitutions;

    /**
     * Configurable, supported jdk versions.
     */
    private final Map<String, String> jdkHomes;
    private final boolean cleanWrapperScripts, excludeGithubFolder, recursiveDefault, failOnError;

    public CdxgenClient() {
        var config = ConfigProvider.getConfig();
        this.cleanWrapperScripts = config.getValue("app.clean_wrapper_scripts", Boolean.TYPE);
        this.excludeGithubFolder = config.getValue("app.exclude_github_folder", Boolean.TYPE);
        this.recursiveDefault = config.getValue("analysis.recursive_default", Boolean.TYPE);
        this.failOnError = config.getValue("cdxgen.fail_on_error", Boolean.TYPE);

        this.allowedEnvSubstitutions = Arrays.stream(
            config.getValue("app.allowed_env_substitutions", String.class).split(",")
        ).map(String::trim).toList();

        // https://github.com/AppThreat/cdxgen#environment-variables
        this.cdxgenEnv = Map.of(
            "GITHUB_TOKEN", config.getValue("github.token", String.class).trim(),
            "USE_GOSUM", config.getValue("cdxgen.use_gosum", Boolean.class).toString(),
            CDXGEN_MAVEN_ARGS, DEFAULT_MAVEN_ARGS,
            "CDX_MAVEN_INCLUDE_TEST_SCOPE", config.getValue("cdxgen.maven_include_test_scope", Boolean.class).toString(),
            "CDXGEN_TIMEOUT_MS", Long.toString(config.getValue("app.analysis_timeout", Duration.class).toMillis())
        );

        jdkHomes = System.getenv().entrySet().stream()
            .filter(e -> e.getKey().matches("JAVA\\d+_HOME"))
            .collect(Collectors.toMap(
                e -> e.getKey().replace("JAVA", "").replace("_HOME", ""),
                Map.Entry::getValue
            ));
    }

    /**
     * `cdxgen` command with the following options:
     * * -o %s # output to file [SBOM_JSON]
     * * %s # optional recursive flag [RECURSIVE_FLAG]
     * * %s # optional fail-on-error flag [FAIL_ON_ERROR_FLAG]
     * * --project-name %s # name of main component of the SBOM, defaulting to the repository name
     */
    private static final String CDXGEN_CMD_FMT = "cdxgen --spec-version 1.4 -o %s%s%s --project-name %s";

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
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .chain(() -> generateSbom(this, metadata))
                .map(result -> parseSbom(this, result, metadata));
        }

    }

    /**
     * Creates a list of commands with each one representing a separate module resulting in an own dependency-track project.
     */
    public List<SbomCreationCommand> createCommands(Path repoDir, String repoName, boolean fetchLicenses, Optional<TechnolinatorConfig> config) {
        var configPaths = buildConfigPaths(config);
        if (configPaths.isEmpty()) {
            return List.of(
                new SbomCreationCommand(
                    repoDir,
                    repoName,
                    buildCdxgenCommand(recursiveDefault, failOnError, repoName),
                    buildEnv(List.of(), fetchLicenses),
                    buildExcludeList(List.of())
                )
            );
        } else {
            return configPaths.stream().map(configPath -> {
                    var projectName = determineProjectName(repoName, configPath);
                    return new SbomCreationCommand(
                        determineAnalysisFolder(repoDir, configPath),
                        projectName,
                        buildCdxgenCommand(analyseRecursive(configPath), failOnError, projectName),
                        buildEnv(configPath, fetchLicenses),
                        buildExcludeList(configPath)
                    );
                }
            ).toList();
        }
    }

    private String buildCdxgenCommand(boolean recursive, boolean failOnError, String projectName) {
        return CDXGEN_CMD_FMT.formatted(
            SBOM_JSON,
            recursive ? RECURSIVE_FLAG : NO_RECURSIVE_FLAG,
            failOnError ? FAIL_ON_ERROR_FLAG : "",
            projectName
        );
    }

    /**
     * Creates a list of all config paths available.
     * Last element of each inner list represents the leaf as actual project config, the preceding elements are ancestors of it.
     */
    List<List<TechnolinatorConfig>> buildConfigPaths(Optional<TechnolinatorConfig> root) {
        var paths = new ArrayList<List<TechnolinatorConfig>>();
        root.ifPresent(config -> buildConfigPaths(config, new ArrayList<>(), paths));
        return paths;
    }

    private void buildConfigPaths(TechnolinatorConfig config, List<TechnolinatorConfig> path, List<List<TechnolinatorConfig>> paths) {
        path.add(config);
        if (config.projects() == null || config.projects().isEmpty()) {
            paths.add(new ArrayList<>(path));
        } else {
            config.projects().forEach(child -> buildConfigPaths(child, path, paths));
        }
        path.remove(path.size() - 1);
    }

    static Uni<ProcessHandler.ProcessResult> generateSbom(SbomCreationCommand cmd, Command.Metadata metadata) {
        metadata.writeToMDC();
        return ProcessHandler.run(cmd.commandLine, cmd.repoDir(), cmd.environment());
    }

    static Result<SBOMGenerationResult> parseSbom(SbomCreationCommand cmd, ProcessHandler.ProcessResult generationResult, Command.Metadata metadata) {
        var sbomFile = cmd.repoDir().resolve(SBOM_JSON);
        metadata.writeToMDC();
        return switch (generationResult) {
            case ProcessHandler.ProcessResult.Success s -> parseSbomFile(sbomFile, cmd.projectName());
            case ProcessHandler.ProcessResult.Failure f -> {
                if (Files.exists(sbomFile)) {
                    Log.warnf(f.cause(), "cdxgen failed for project '%s', but sbom file was yet created, trying to parse it", cmd.projectName());
                    yield parseSbomFile(sbomFile, cmd.projectName());
                } else {
                    Log.warnf(f.cause(), "cdxgen failed for project '%s': %s", cmd.projectName(), cmd.commandLine);
                    yield Result.failure(f.cause());
                }
            }
        };
    }

    static Result<SBOMGenerationResult> parseSbomFile(Path sbomFile, String projectName) {
        if (!Files.exists(sbomFile)) {
            return Result.success(SBOMGenerationResult.none());
        } else if (Files.isReadable(sbomFile)) {
            return readSbomFile(sbomFile, projectName);
        } else {
            throw new IllegalStateException("Cannot read file " + sbomFile.toAbsolutePath());
        }
    }

    private static Result<SBOMGenerationResult> readSbomFile(Path sbomFile, String projectName) {
        try {
            final var sbomContent = Files.readAllBytes(sbomFile);

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
                return Result.success(SBOMGenerationResult.none());
            } else if (named) {
                return Result.success(new SBOMGenerationResult.Proper(sbom, group, name, version, validationResult, sbomFile, projectName));
            } else {
                return Result.success(new SBOMGenerationResult.Fallback(sbom, validationResult, sbomFile, projectName));
            }
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    static boolean isEmpty(Collection<?> value) {
        return value == null || value.isEmpty();
    }

    static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    Map<String, String> buildEnv(List<TechnolinatorConfig> configPath, boolean fetchLicenses) {
        var context = new HashMap<>(cdxgenEnv);
        context.put(CDXGEN_FETCH_LICENSE, Boolean.toString(fetchLicenses));

        String gradleEnv = sliceConfig(configPath, TechnolinatorConfig::gradle, TechnolinatorConfig.GradleConfig::args)
            .stream().reduce(new ArrayList<>(), CdxgenClient::reduceList).stream().map(this::resolveEnvVars).collect(Collectors.joining(" "));
        if (!gradleEnv.isBlank()) {
            context.put(CDXGEN_GRADLE_ARGS, gradleEnv);
        }

        String mavenEnv = sliceConfig(configPath, TechnolinatorConfig::maven, TechnolinatorConfig.MavenConfig::args)
            .stream().reduce(new ArrayList<>(), CdxgenClient::reduceList).stream().map(this::resolveEnvVars).collect(Collectors.joining(" "));
        if (!mavenEnv.isBlank()) {
            context.put(CDXGEN_MAVEN_ARGS, DEFAULT_MAVEN_ARGS + " " + mavenEnv);
        }

        Map<String, String> env = sliceConfig(configPath, TechnolinatorConfig::env)
            .stream().reduce(new HashMap<>(), CdxgenClient::reduceMap).entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> resolveEnvVars(e.getValue())
                )
            );
        context.putAll(env);

        String jdkHome = sliceConfig(configPath, TechnolinatorConfig::jdk, TechnolinatorConfig.JdkConfig::version)
            .stream().map(jdkHomes::get).reduce(JAVA_HOME, (a, e) -> e);
        context.put("JAVA_HOME", jdkHome);

        return context;
    }

    static <T> List<T> reduceList(List<T> aggregate, List<T> sample) {
        aggregate.addAll(sample);
        return aggregate;
    }

    static Map<String, String> reduceMap(Map<String, String> aggregate, Map<String, String> sample) {
        aggregate.putAll(sample);
        return aggregate;
    }

    static <R> List<R> sliceConfig(List<TechnolinatorConfig> path, Function<TechnolinatorConfig, R> extractor) {
        return path.stream().map(extractor).filter(Objects::nonNull).collect(Collectors.toList());
    }

    static <I, R> List<R> sliceConfig(List<TechnolinatorConfig> path, Function<TechnolinatorConfig, I> extractor1, Function<I, R> extractor2) {
        return path.stream().map(extractor1).filter(Objects::nonNull).map(extractor2).filter(Objects::nonNull).collect(Collectors.toList());
    }

    List<String> buildExcludeList(List<TechnolinatorConfig> path) {
        var repoConfigExcludes = sliceConfig(path, TechnolinatorConfig::analysis, TechnolinatorConfig.AnalysisConfig::excludes)
            .stream().flatMap(List::stream).toList();
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

    static Path determineAnalysisFolder(Path repoDir, List<TechnolinatorConfig> path) {
        var includePaths = sliceConfig(path, TechnolinatorConfig::analysis, TechnolinatorConfig.AnalysisConfig::location)
            .stream().map(String::trim)
            .map(p -> p.startsWith("/") ? p.substring(1) : p)
            .map(p -> p.endsWith("/") ? p.substring(0, p.length() - 1) : p)
            .collect(Collectors.joining("/"));

        return repoDir.resolve(includePaths);
    }

    String determineProjectName(String repoName, List<TechnolinatorConfig> path) {
        var names = sliceConfig(path, TechnolinatorConfig::project, TechnolinatorConfig.ProjectConfig::name);
        if (names.isEmpty()) {
            return repoName;
        } else {
            return repoName + names.stream().map(String::trim).collect(Collectors.joining("-", "-", ""));
        }
    }

    boolean analyseRecursive(List<TechnolinatorConfig> path) {
        var recursiveConfig = sliceConfig(path, TechnolinatorConfig::analysis, TechnolinatorConfig.AnalysisConfig::recursive);
        if (recursiveConfig.isEmpty()) {
            return recursiveDefault;
        } else {
            return recursiveConfig.get(recursiveConfig.size() - 1);
        }
    }

    /**
     * Replaces variable templates in form ${VAR} with the actual env value
     */
    String resolveEnvVars(String value) {
        var matcher = ENV_VAR_PATTERN.matcher(value);
        while (matcher.find()) {
            var envVar = matcher.group(1);
            String envVal = allowedEnvSubstitutions.contains(envVar) ? System.getenv(envVar) : envVar;
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
            return ProcessHandler.run("rm -rf " + excludes, dir, Map.of())
                .onItem().ignore().andContinueWithNull();
        }
    }

    public sealed interface SBOMGenerationResult {

        String projectName();

        record Proper(
            Bom sbom,
            String group,
            String name,
            String version,
            List<ParseException> validationIssues,
            Path sbomFile,
            String projectName
        ) implements SBOMGenerationResult {
        }

        record Fallback(
            Bom sbom,
            List<ParseException> validationIssues,
            Path sbomFile,
            String projectName
        ) implements SBOMGenerationResult {
        }

        record None() implements SBOMGenerationResult {
            public String projectName() {
                throw new IllegalStateException();
            }
        }

        static SBOMGenerationResult none() {
            return new None();
        }
    }

}
