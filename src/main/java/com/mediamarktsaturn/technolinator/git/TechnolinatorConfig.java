package com.mediamarktsaturn.technolinator.git;

import java.util.List;
import java.util.Map;

/**
 * Repository specific configuration options
 */
public record TechnolinatorConfig(
    Boolean enable,
    ProjectConfig project,
    AnalysisConfig analysis,
    GradleConfig gradle,
    MavenConfig maven,
    JdkConfig jdk,
    Map<String, String> env
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
        Boolean multiProject,
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
