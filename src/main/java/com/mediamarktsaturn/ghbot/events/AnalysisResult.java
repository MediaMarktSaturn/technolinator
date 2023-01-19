package com.mediamarktsaturn.ghbot.events;

public record AnalysisResult(
    boolean success,
    String url
) {
}
