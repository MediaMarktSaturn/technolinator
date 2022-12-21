package com.mediamarktsaturn.ghbot.events;

import java.net.URL;

public record PushEvent(
    URL repoUrl,
    String pushRef,
    String defaultBranch
) {
}
