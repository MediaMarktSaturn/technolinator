package com.mediamarktsaturn.technolinator;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConfigBuilder {
    private Boolean enable = null;
    private Boolean enablePullRequestReport = null;
    private TechnolinatorConfig.ProjectConfig project;
    private TechnolinatorConfig.AnalysisConfig analysis;
    private TechnolinatorConfig.GradleConfig gradle;
    private TechnolinatorConfig.MavenConfig maven;
    private Map<String, String> env;
    private TechnolinatorConfig.JdkConfig jdk;

    private List<TechnolinatorConfig> projects;

    public static ConfigBuilder create() {
        return new ConfigBuilder();
    }

    public static ConfigBuilder create(String projectName) {
        return create().project(new TechnolinatorConfig.ProjectConfig(projectName));
    }

    public static TechnolinatorConfig build(String projectName) {
        return create(projectName).build();
    }

    public TechnolinatorConfig build() {
        return new TechnolinatorConfig(
            enable,
            enablePullRequestReport,
            project,
            analysis,
            gradle,
            maven,
            jdk,
            env,
            projects
        );
    }

    public ConfigBuilder enable(boolean enable) {
        this.enable = enable;
        return this;
    }

    public ConfigBuilder enablePullRequestReport(boolean enablePullRequestReport) {
        this.enablePullRequestReport = enablePullRequestReport;
        return this;
    }

    public ConfigBuilder project(TechnolinatorConfig.ProjectConfig project) {
        this.project = project;
        return this;
    }

    public ConfigBuilder analysis(TechnolinatorConfig.AnalysisConfig analysis) {
        this.analysis = analysis;
        return this;
    }

    public ConfigBuilder gradle(TechnolinatorConfig.GradleConfig gradle) {
        this.gradle = gradle;
        return this;
    }

    public ConfigBuilder maven(TechnolinatorConfig.MavenConfig maven) {
        this.maven = maven;
        return this;
    }

    public ConfigBuilder env(Map<String, String> env) {
        this.env = env;
        return this;
    }

    public ConfigBuilder jdk(TechnolinatorConfig.JdkConfig jdk) {
        this.jdk = jdk;
        return this;
    }

    public ConfigBuilder addSubProject(TechnolinatorConfig subprojectConfig) {
        if (this.projects == null) {
            this.projects = new ArrayList<>();
        }
        this.projects.add(subprojectConfig);
        return this;
    }
}

