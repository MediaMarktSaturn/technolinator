package com.mediamarktsaturn.ghbot.events;

import java.net.URL;
import java.util.Optional;
import java.util.function.Consumer;

import org.kohsuke.github.GHEventPayload;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;

public record PushEvent(
    GHEventPayload.Push pushPayload,
    Consumer<AnalysisResult> resultCallback,
    Optional<TechnolinatorConfig> config
) {
    public String getBranch() {
        return pushRef().replaceFirst("refs/heads/", "");
    }

    public URL repoUrl() {
        return pushPayload.getRepository().getUrl();
    }

    public String pushRef() {
        return pushPayload.getRef();
    }

    public String defaultBranch() {
        return pushPayload.getRepository().getDefaultBranch();
    }
}
