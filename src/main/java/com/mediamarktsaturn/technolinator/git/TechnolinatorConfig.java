package com.mediamarktsaturn.technolinator.git;

import java.util.List;
import java.util.Map;

/**
 * Repository specific configuration options
 */
public record TechnolinatorConfig(
    Boolean enable,
    Boolean enablePullRequestReport,
    ProjectConfig project,
    AnalysisConfig analysis,
    GradleConfig gradle,
    MavenConfig maven,
    JdkConfig jdk,
    Map<String, String> env,
    List<TechnolinatorConfig> projects
) {

    public record ProjectConfig(
        String name
    ) {
    }

    public record AnalysisConfig(
        String location,
        Boolean recursive,
        List<String> excludes
    ) {
    }

    public record GradleConfig(
        List<String> args
    ) {
    }

    public record MavenConfig(
        List<String> args
    ) {
    }

    public record JdkConfig(
        String version
    ) {
    }
}
