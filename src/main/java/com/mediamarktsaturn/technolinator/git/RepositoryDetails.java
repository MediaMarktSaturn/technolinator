package com.mediamarktsaturn.technolinator.git;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record RepositoryDetails(
    String name,
    String version,
    String description,
    String websiteUrl,
    String vcsUrl,
    List<String> topics
) {
    public RepositoryDetails withAdditionalTopics(String... addTopics) {
        var allTopics = new ArrayList<>(topics);
        allTopics.addAll(Arrays.asList(addTopics));
        return new RepositoryDetails(name, version, description, websiteUrl, vcsUrl, allTopics);
    }
}
