package com.mediamarktsaturn.ghbot.git;

public record TechnolinatorConfig(
    Boolean enable,
    ProjectConfig project,
    AnalysisConfig analysis
) {

    public record ProjectConfig(
        String name
    ) {}

    public record AnalysisConfig(
        String location
    ) {}
}
