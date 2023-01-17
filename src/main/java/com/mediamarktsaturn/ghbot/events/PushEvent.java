package com.mediamarktsaturn.ghbot.events;

import java.net.URL;
import java.util.Optional;

import com.mediamarktsaturn.ghbot.git.TechnolinatorConfig;

public record PushEvent(
    URL repoUrl,
    String pushRef,
    String defaultBranch,
    Optional<TechnolinatorConfig> config
) {
}
