package com.mediamarktsaturn.ghbot.git;

import java.util.List;

public record TechnolinatorConfig(
    Boolean enable,
    ProjectConfig project,
    AnalysisConfig analysis,
    GradleConfig gradle,
    MavenConfig maven
) {

    public record ProjectConfig(
        String name
    ) {
    }

    public record AnalysisConfig(
        String location,
        Boolean recursive
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
}
