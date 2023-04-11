package com.mediamarktsaturn.technolinator;

import java.util.Map;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;

public class ConfigBuilder {
    private Boolean enable = null;
    private TechnolinatorConfig.ProjectConfig project;
    private TechnolinatorConfig.AnalysisConfig analysis;
    private TechnolinatorConfig.GradleConfig gradle;
    private TechnolinatorConfig.MavenConfig maven;
    private Map<String, String> env;
    private TechnolinatorConfig.JdkConfig jdk;

    public static ConfigBuilder create() {
        return new ConfigBuilder();
    }

    public TechnolinatorConfig build() {
        return new TechnolinatorConfig(
            enable,
            project,
            analysis,
            gradle,
            maven,
            jdk,
            env
        );
    }

    public ConfigBuilder enable(boolean enable) {
        this.enable = enable;
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
}
