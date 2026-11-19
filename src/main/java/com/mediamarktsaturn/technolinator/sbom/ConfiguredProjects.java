package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tells which projects a repository configuration (the {@code projects} hierarchy of {@code .github/technolinator.yml}) describes,
 * and how they are named in Dependency-Track.
 * <p>
 * This is the one and only implementation of that, used for the analysis ({@link CdxgenClient}) as well as for housekeeping.
 */
public final class ConfiguredProjects {

    private ConfiguredProjects() {
    }

    /**
     * Creates a list of all config paths available.
     * Last element of each inner list represents the leaf as actual project config, the preceding elements are ancestors of it.
     */
    public static List<List<TechnolinatorConfig>> configPaths(Optional<TechnolinatorConfig> root) {
        var paths = new ArrayList<List<TechnolinatorConfig>>();
        root.ifPresent(config -> collectConfigPaths(config, new ArrayList<>(), paths));
        return paths;
    }

    private static void collectConfigPaths(TechnolinatorConfig config, List<TechnolinatorConfig> path, List<List<TechnolinatorConfig>> paths) {
        path.add(config);
        if (config.projects() == null || config.projects().isEmpty()) {
            paths.add(new ArrayList<>(path));
        } else {
            config.projects().forEach(child -> collectConfigPaths(child, path, paths));
        }
        path.remove(path.size() - 1);
    }

    /**
     * @param path a config path as created by {@link #configPaths}
     * @return the name of the Dependency-Track project of the repository's module described by the path
     */
    public static String projectName(String repoName, List<TechnolinatorConfig> path) {
        var names = path.stream()
            .map(TechnolinatorConfig::project)
            .filter(Objects::nonNull)
            .map(TechnolinatorConfig.ProjectConfig::name)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        if (names.isEmpty()) {
            return repoName;
        } else {
            // the name ends up as a single argument of the command line, so it must not contain whitespace
            return repoName + names.stream().map(String::trim).collect(Collectors.joining("-", "-", "")).replaceAll("\\s+", "-");
        }
    }

    /**
     * @return the names of all projects an analysis of the repository with the given configuration (if any) uploads a BOM for,
     * that are the modules described by the configuration, or the repository itself if there is none
     */
    public static Set<String> projectNames(String repoName, Optional<TechnolinatorConfig> config) {
        var paths = configPaths(config);
        if (paths.isEmpty()) {
            return Set.of(repoName);
        }
        return paths.stream()
            .map(path -> projectName(repoName, path))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
