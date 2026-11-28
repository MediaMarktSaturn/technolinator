package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.os.ChildEnvironment;
import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import com.mediamarktsaturn.technolinator.os.ProcessHandler;
import com.mediamarktsaturn.technolinator.os.Util;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.parsers.JsonParser;
import org.eclipse.microprofile.config.ConfigProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wraps around 'cdxgen' for SBOM creation
 */
@ApplicationScoped
public class CdxgenClient {

    static final String SBOM_JSON = "sbom.json";

    /**
     * Name of the command a failed {@code prepare} script is reported as.
     */
    public static final String PREPARE_COMMAND = "prepare";

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

    /**
     * cdxgen option --required-only: Include only the packages with required scope on the SBOM.
     */
    private static final String REQUIRED_ONLY_FLAG = " --required-only";

    /**
     * cdxgen option --evidence: Generate SBOM with evidence for supported languages.
     */
    private static final String EVIDENCE_FLAG = " --evidence";

    /**
     * cdxgen option --include-formulation: Generate formulation section using git metadata.
     */
    private static final String FORMULATION_FLAG = " --include-formulation";


    // cdxgen ENV variable names
    private static final String CDXGEN_GRADLE_ARGS = "GRADLE_ARGS";
    private static final String CDXGEN_MAVEN_ARGS = "MVN_ARGS";
    private static final String CDXGEN_FETCH_LICENSE = "FETCH_LICENSE";
    private static final String JAVA_HOME = System.getenv("JAVA_HOME");
    private static final String CDXGEN_DEBUG = "CDXGEN_DEBUG_MODE";
    private static final String GOFLAGS = "GOFLAGS";

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
    final List<String> allowedEnvSubstitutions;

    /**
     * Whether {@code prepare} scripts of repositories are run at all.
     */
    private final boolean prepareEnabled;

    /**
     * Configurable, supported jdk versions.
     */
    private final Map<String, String> jdkHomes;
    private final boolean cleanWrapperScripts, excludeGithubFolder, recursiveDefault, requiredScopeOnlyDefault, evidenceDefault, formulationDefault, failOnError, cdxgenDebug;

    public CdxgenClient() {
        var config = ConfigProvider.getConfig();
        this.cleanWrapperScripts = config.getValue("app.clean_wrapper_scripts", Boolean.TYPE);
        this.excludeGithubFolder = config.getValue("app.exclude_github_folder", Boolean.TYPE);
        this.recursiveDefault = config.getValue("analysis.recursive_default", Boolean.TYPE);
        this.requiredScopeOnlyDefault = config.getValue("cdxgen.required_scope_only", Boolean.TYPE);
        this.evidenceDefault = config.getValue("cdxgen.evidence", Boolean.TYPE);
        this.formulationDefault = config.getValue("cdxgen.formulation", Boolean.TYPE);
        this.failOnError = config.getValue("cdxgen.fail_on_error", Boolean.TYPE);
        this.cdxgenDebug = config.getValue("cdxgen.debug", Boolean.TYPE);
        this.prepareEnabled = config.getValue("app.prepare.enabled", Boolean.TYPE);

        this.allowedEnvSubstitutions = config.getOptionalValue("app.allowed_env_substitutions", String.class)
            .filter(str -> !str.isBlank())
            .map(str -> Arrays.stream(str.split(",")).map(String::trim).toList())
            .orElse(Collections.emptyList());

        // https://github.com/AppThreat/cdxgen#environment-variables
        var defaultEnv = new HashMap<>(Map.of(
            "GITHUB_TOKEN", config.getValue("github.token", String.class).trim(),
            "USE_GOSUM", config.getValue("cdxgen.use_gosum", Boolean.class).toString(),
            CDXGEN_MAVEN_ARGS, DEFAULT_MAVEN_ARGS,
            "PREFER_MAVEN_DEPS_TREE", config.getValue("cdxgen.prefer_mvn_deps_tree", Boolean.TYPE).toString(),
            "CDX_MAVEN_INCLUDE_TEST_SCOPE", String.valueOf(!requiredScopeOnlyDefault),
            "GRADLE_MULTI_THREADED", "true",
            "CDXGEN_TIMEOUT_MS", Long.toString(config.getValue("app.analysis_timeout", Duration.class).toMillis())
        ));

        // Go flags applied to all go invocations of cdxgen, e.g. `-e` to tolerate unresolvable packages (generated sources not committed)
        config.getOptionalValue("cdxgen.go_flags", String.class)
            .map(String::trim)
            .filter(flags -> !flags.isEmpty())
            .ifPresent(flags -> defaultEnv.put(GOFLAGS, flags));
        this.cdxgenEnv = Map.copyOf(defaultEnv);

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
     * * %s # optional --required-only flag
     * * %s # optional --evidence flag
     * * %s # optional --include-formulation flag
     * * --project-name %s # name of main component of the SBOM, defaulting to the repository name
     * * --no-validate # disable cdxgen validation as we try to process everything
     */
    private static final String CDXGEN_CMD_FMT = "cdxgen --spec-version 1.7 -o %s%s%s%s%s%s --project-name %s --no-validate";

    /**
     * @param location the analysed folder relative to the repository root, empty for the root itself
     * @param prepare the script to run ahead of cdxgen, null if there is none to run
     * @param preparationSkipped why a configured script is not run (disabled, untrusted code), null if it is not skipped
     */
    public record SbomCreationCommand(
        Path repoDir,
        String projectName,
        String commandLine,
        Map<String, String> environment,
        List<String> excludeFiles,
        String location,
        TechnolinatorConfig.PrepareConfig prepare,
        String preparationSkipped
    ) implements Command<SBOMGenerationResult> {

        public SbomCreationCommand(Path repoDir, String projectName, String commandLine, Map<String, String> environment, List<String> excludeFiles) {
            this(repoDir, projectName, commandLine, environment, excludeFiles, "", null, null);
        }

        public SbomCreationCommand(Path repoDir, String projectName, String commandLine, Map<String, String> environment, List<String> excludeFiles,
                                   String location, TechnolinatorConfig.PrepareConfig prepare) {
            this(repoDir, projectName, commandLine, environment, excludeFiles, location, prepare, null);
        }

        @Override
        public Uni<Result<SBOMGenerationResult>> execute(Metadata metadata) {
            metadata.writeToMDC();
            return Uni.createFrom().deferred(() -> {
                if (preparationSkipped != null) {
                    Log.warnf("Skipping the prepare script of project '%s': %s", projectName, preparationSkipped);
                }
                var scratch = new Preparation.Scratch();
                return removeExcludedFiles(this)
                    .chain(() -> Preparation.prepare(this, scratch))
                    .chain(env -> generateSbom(this, metadata, env))
                    .map(result -> parseSbom(this, result, metadata))
                    .onTermination().invoke(scratch::close)
                    // a failing project must neither affect the others nor hide the checkout from the cleanup
                    .onFailure().recoverWithItem(failure -> {
                        Log.errorf(failure, "Analysis of project '%s' failed unexpectedly", projectName);
                        return Result.failure(failure);
                    });
            });
        }
    }

    /**
     * Creates a list of commands with each one representing a separate module resulting in an own dependency-track project.
     * The {@code prepare} scripts of the configuration are run.
     */
    public List<SbomCreationCommand> createCommands(Path repoDir, String repoName, boolean fetchLicenses, Optional<TechnolinatorConfig> config) {
        return createCommands(repoDir, repoName, fetchLicenses, config, true);
    }

    /**
     * Creates a list of commands with each one representing a separate module resulting in an own dependency-track project.
     *
     * @param trusted whether the code to analyse is trusted to run scripts, which is not the case for pull requests of forks
     */
    public List<SbomCreationCommand> createCommands(Path repoDir, String repoName, boolean fetchLicenses, Optional<TechnolinatorConfig> config, boolean trusted) {
        var configPaths = buildConfigPaths(config);
        if (configPaths.isEmpty()) {
            return List.of(
                new SbomCreationCommand(
                    repoDir,
                    repoName,
                    buildCdxgenCommand(recursiveDefault, requiredScopeOnlyDefault, evidenceDefault, formulationDefault, failOnError, repoName),
                    buildEnv(List.of(), fetchLicenses),
                    buildExcludeList(List.of()),
                    "",
                    null,
                    null
                )
            );
        } else {
            return configPaths.stream().map(configPath -> {
                    var projectName = determineProjectName(repoName, configPath);
                    var analysisFolder = determineAnalysisFolder(repoDir, configPath);
                    // the most specific one wins, a blank script disables an inherited one
                    var prepare = sliceConfig(configPath, TechnolinatorConfig::prepare).stream().reduce((a, b) -> b)
                        .filter(p -> p.script() != null && !p.script().isBlank());
                    var skipped = prepare.isEmpty() ? null : preparationSkipReason(trusted);
                    return new SbomCreationCommand(
                        analysisFolder,
                        projectName,
                        buildCdxgenCommand(analyseRecursive(configPath), requiredScopeOnlyFlag(configPath), evidenceFlag(configPath), formulationFlag(configPath), failOnError, projectName),
                        buildEnv(configPath, fetchLicenses),
                        buildExcludeList(configPath),
                        relativeLocation(repoDir, analysisFolder),
                        skipped == null ? prepare.orElse(null) : null,
                        skipped
                    );
                }
            ).toList();
        }
    }

    private String preparationSkipReason(boolean trusted) {
        if (!prepareEnabled) {
            return "running prepare scripts is disabled by the operator of this Technolinator instance";
        }
        if (!trusted) {
            return "the code of pull requests from forks isn't trusted to run scripts";
        }
        return null;
    }

    private static String relativeLocation(Path repoDir, Path analysisFolder) {
        try {
            return repoDir.relativize(analysisFolder).normalize().toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private String buildCdxgenCommand(boolean recursive, boolean requiredOnly, boolean evidence, boolean formulation, boolean failOnError, String projectName) {
        return CDXGEN_CMD_FMT.formatted(
            SBOM_JSON,
            recursive ? RECURSIVE_FLAG : NO_RECURSIVE_FLAG,
            failOnError ? FAIL_ON_ERROR_FLAG : "",
            requiredOnly ? REQUIRED_ONLY_FLAG : "",
            evidence ? EVIDENCE_FLAG : "",
            formulation ? FORMULATION_FLAG : "",
            projectName
        );
    }

    /**
     * Creates a list of all config paths available.
     * Last element of each inner list represents the leaf as actual project config, the preceding elements are ancestors of it.
     */
    List<List<TechnolinatorConfig>> buildConfigPaths(Optional<TechnolinatorConfig> root) {
        return ConfiguredProjects.configPaths(root);
    }

    static Map<String, String> analysisEnvironment(SbomCreationCommand cmd) {
        return childEnvironment(System.getenv(), inheritedEnvNames(), cmd.environment());
    }

    private static List<String> inheritedEnvNames() {
        return ConfigProvider.getConfig().getOptionalValue("app.analysis.cdxgen.inherited_env", String.class)
            .filter(str -> !str.isBlank())
            .map(str -> Arrays.stream(str.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList())
            .orElse(Collections.emptyList());
    }

    /**
     * The environment of a {@code prepare} script: the one of cdxgen, but without what Technolinator itself got hold of.
     */
    static Map<String, String> preparationEnvironment(SbomCreationCommand cmd) {
        var sensitive = Optional.ofNullable(System.getenv("SENSITIVE_ENV_VARS"))
            .map(names -> Arrays.stream(names.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList())
            .orElse(Collections.emptyList());
        return preparationEnvironment(System.getenv(), inheritedEnvNames(), sensitive, cmd.environment());
    }

    /**
     * Secrets are removed from what is inherited from Technolinator, and Technolinators own token is removed from the explicit variables.
     * Everything else of the explicit variables is kept, as that is what the repository configured (e.g. credentials for its package repositories).
     */
    static Map<String, String> preparationEnvironment(Map<String, String> parentEnv, List<String> inherited, List<String> sensitiveNames, Map<String, String> explicit) {
        var env = ChildEnvironment.withoutSecrets(ChildEnvironment.inherited(parentEnv, inherited), sensitiveNames);
        explicit.forEach((name, value) -> {
            if (!name.equals("GITHUB_TOKEN")) {
                env.put(name, value);
            }
        });
        return env;
    }

    static Uni<ProcessHandler.ProcessResult> generateSbom(SbomCreationCommand cmd, Command.Metadata metadata, Map<String, String> env) {
        metadata.writeToMDC();
        return ProcessHandler.runIsolated(cmd.commandLine, cmd.repoDir(), env);
    }

    /**
     * The environment cdxgen runs with: the explicitly configured one on top of the part of Technolinators environment
     * the tools need, see {@link ChildEnvironment}.
     */
    static Map<String, String> childEnvironment(Map<String, String> parentEnv, List<String> inherited, Map<String, String> explicit) {
        var env = ChildEnvironment.inherited(parentEnv, inherited);
        env.putAll(explicit);
        return env;
    }

    static Result<SBOMGenerationResult> parseSbom(SbomCreationCommand cmd, ProcessHandler.ProcessResult generationResult, Command.Metadata metadata) {
        var sbomFile = cmd.repoDir().resolve(SBOM_JSON);
        metadata.writeToMDC();
        return switch (generationResult) {
            case ProcessHandler.ProcessResult.Success s -> parseSbomFile(sbomFile, cmd.projectName());
            case ProcessHandler.ProcessResult.Failure f -> {
                if (cmd.prepare() != null) {
                    // a SBOM of a repository that wasn't prepared completely is of no use
                    yield Result.failure(f.cause() instanceof ProcessFailedException pfe ? pfe.withProject(cmd.projectName(), cmd.location()) : f.cause());
                } else if (Files.exists(sbomFile)) {
                    Log.warnf(f.cause(), "cdxgen failed for project '%s', but sbom file was yet created, trying to parse it", cmd.projectName());
                    yield parseSbomFile(sbomFile, cmd.projectName());
                } else {
                    Log.warnf(f.cause(), "cdxgen failed for project '%s': %s", cmd.projectName(), cmd.commandLine);
                    yield Result.failure(f.cause() instanceof ProcessFailedException pfe ? withSkipNote(pfe.withProject(cmd.projectName(), cmd.location()), cmd) : f.cause());
                }
            }
        };
    }

    private static ProcessFailedException withSkipNote(ProcessFailedException failure, SbomCreationCommand cmd) {
        return cmd.preparationSkipped() == null ? failure
            : failure.withNote("The `prepare` script of this project was not run, because %s.".formatted(cmd.preparationSkipped()));
    }

    static Result<SBOMGenerationResult> parseSbomFile(Path sbomFile, String projectName) {
        if (Files.isSymbolicLink(sbomFile)) {
            return Result.failure(new IllegalStateException("The SBOM file is a symbolic link: " + sbomFile.toAbsolutePath()));
        }
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
            if (isNotBlank(sbom.getSpecVersion()) && isNotBlank(sbom.getBomFormat())) {
                return Result.success(SBOMGenerationResult.yield(sbom, validationResult, sbomFile, projectName));
            } else {
                return Result.success(SBOMGenerationResult.none());
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

        if (cdxgenDebug) {
            context.put(CDXGEN_DEBUG, "debug");
        }

        String gradleEnv = joinedArgs(configPath, TechnolinatorConfig::gradle, TechnolinatorConfig.GradleConfig::args);
        if (!gradleEnv.isBlank()) {
            context.put(CDXGEN_GRADLE_ARGS, gradleEnv);
        }

        String mavenEnv = joinedArgs(configPath, TechnolinatorConfig::maven, TechnolinatorConfig.MavenConfig::args);
        if (!mavenEnv.isBlank()) {
            context.put(CDXGEN_MAVEN_ARGS, DEFAULT_MAVEN_ARGS + " " + mavenEnv);
        }

        sliceConfig(configPath, TechnolinatorConfig::env)
            .forEach(env -> env.forEach((key, value) -> context.put(key, resolveEnvVars(value))));

        var jdkVersion = sliceConfig(configPath, TechnolinatorConfig::jdk, TechnolinatorConfig.JdkConfig::version).stream().reduce((a, b) -> b);
        var jdkHome = jdkVersion.map(version -> {
            var home = jdkHomes.get(version);
            if (home == null) {
                throw new IllegalArgumentException("JDK version '%s' is not available, supported are: %s".formatted(version, jdkHomes.keySet()));
            }
            return home;
        }).orElse(JAVA_HOME);
        if (jdkHome != null) {
            context.put("JAVA_HOME", jdkHome);
        }

        return context;
    }

    /**
     * Args of the given tool along the config path, in hierarchical order, with the env variables resolved.
     */
    private <T> String joinedArgs(List<TechnolinatorConfig> path, Function<TechnolinatorConfig, T> tool, Function<T, List<String>> args) {
        return sliceConfig(path, tool, args).stream().flatMap(List::stream).map(this::resolveEnvVars).collect(Collectors.joining(" "));
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

        var folder = repoDir.resolve(includePaths);
        if (!folder.normalize().startsWith(repoDir.normalize())) {
            throw new IllegalArgumentException("Analysis location '" + includePaths + "' is not allowed to step out of the repository");
        }
        return folder;
    }

    String determineProjectName(String repoName, List<TechnolinatorConfig> path) {
        return ConfiguredProjects.projectName(repoName, path);
    }

    boolean analyseRecursive(List<TechnolinatorConfig> path) {
        return lastConfigured(path, TechnolinatorConfig.AnalysisConfig::recursive, recursiveDefault);
    }

    boolean requiredScopeOnlyFlag(List<TechnolinatorConfig> path) {
        return lastConfigured(path, TechnolinatorConfig.AnalysisConfig::requiredScopeOnly, requiredScopeOnlyDefault);
    }

    boolean evidenceFlag(List<TechnolinatorConfig> path) {
        return lastConfigured(path, TechnolinatorConfig.AnalysisConfig::evidence, evidenceDefault);
    }

    boolean formulationFlag(List<TechnolinatorConfig> path) {
        return lastConfigured(path, TechnolinatorConfig.AnalysisConfig::formulation, formulationDefault);
    }

    /**
     * The most specific analysis option configured along the path, the default if there is none.
     */
    private static boolean lastConfigured(List<TechnolinatorConfig> path, Function<TechnolinatorConfig.AnalysisConfig, Boolean> option, boolean defaultValue) {
        var configured = sliceConfig(path, TechnolinatorConfig::analysis, option);
        return configured.isEmpty() ? defaultValue : configured.getLast();
    }

    /**
     * Replaces variable templates in form ${VAR} with the actual env value
     */
    String resolveEnvVars(String value) {
        return ENV_VAR_PATTERN.matcher(value).replaceAll(match -> {
            var envVar = match.group(1);
            var envVal = allowedEnvSubstitutions.contains(envVar) ? System.getenv(envVar) : envVar;
            return Matcher.quoteReplacement(envVal == null ? "" : envVal);
        });
    }

    /**
     * Removes the files and folders to exclude from the analysis. Names are relative to the repository directory,
     * anything pointing outside of it is ignored.
     */
    static Uni<Void> removeExcludedFiles(SbomCreationCommand cmd) {
        if (cmd.excludeFiles().isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().item(Unchecked.supplier(() -> {
            var root = cmd.repoDir().toAbsolutePath().normalize();
            for (var exclude : cmd.excludeFiles()) {
                var target = root.resolve(exclude.trim()).normalize();
                if (target.equals(root) || !target.startsWith(root)) {
                    Log.warnf("Ignoring exclude '%s', as it doesn't point into the repository", exclude);
                } else {
                    Util.removeRecursively(target);
                }
            }
            return (Void) null;
        })).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .onFailure().invoke(e -> Log.warnf(e, "Failed to remove excluded files from %s", cmd.repoDir()))
            .onFailure().recoverWithNull();
    }

    public sealed interface SBOMGenerationResult {

        record Yield(
            Bom sbom,
            List<ParseException> validationIssues,
            Path sbomFile,
            String projectName
        ) implements SBOMGenerationResult {
        }

        record None() implements SBOMGenerationResult {
        }

        static SBOMGenerationResult none() {
            return new None();
        }

        static SBOMGenerationResult yield(Bom sbom, List<ParseException> validationIssues, Path sbomFile, String projectName) {
            return new Yield(sbom, validationIssues, sbomFile, projectName);
        }
    }

}
