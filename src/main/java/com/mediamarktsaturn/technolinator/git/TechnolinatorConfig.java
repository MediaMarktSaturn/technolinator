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
    List<TechnolinatorConfig> projects,
    ReportingConfig reporting,
    PrepareConfig prepare
) {

    public TechnolinatorConfig(Boolean enable, Boolean enablePullRequestReport, ProjectConfig project,
                               AnalysisConfig analysis, GradleConfig gradle, MavenConfig maven, JdkConfig jdk,
                               Map<String, String> env, List<TechnolinatorConfig> projects, ReportingConfig reporting) {
        this(enable, enablePullRequestReport, project, analysis, gradle, maven, jdk, env, projects, reporting, null);
    }

    public record ProjectConfig(
        String name
    ) {
    }

    public record AnalysisConfig(
        String location,
        Boolean recursive,
        Boolean requiredScopeOnly,
        Boolean evidence,
        Boolean formulation,
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

    /**
     * Opt-out of reporting failed analyses to the repository, both default to enabled.
     *
     * @param issues the status issue for failing analyses of the default branch
     * @param pullRequestComments status in the pull-request comment for failing analyses of pull-requests
     */
    public record ReportingConfig(
        Boolean issues,
        Boolean pullRequestComments
    ) {
    }

    public record JdkConfig(
        String version
    ) {
    }

    /**
     * A script run in the analysed folder ahead of cdxgen, to prepare the repository for it (generate sources, resolve
     * dependencies of ecosystems cdxgen can't handle on a plain checkout, ...).
     *
     * @param script the content of a bash script, run with {@code -euo pipefail}
     */
    public record PrepareConfig(
        String script
    ) {
    }
}
