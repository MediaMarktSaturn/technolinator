package com.mediamarktsaturn.ghbot.events;

import java.net.URL;
import java.util.Optional;
import java.util.function.Consumer;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;

public record PushEvent(
    URL repoUrl,
    String pushRef,
    String defaultBranch,
    Consumer<AnalysisResult> resultCallback,
    Optional<TechnolinatorConfig> config
) {
    public String getBranch() {
        return pushRef.replaceFirst("refs/heads/", "");
    }
}
