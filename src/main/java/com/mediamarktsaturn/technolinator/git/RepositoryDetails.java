package com.mediamarktsaturn.technolinator.git;

import java.util.ArrayList;
import java.util.List;

public record RepositoryDetails(
    String name,
    String version,
    String description,
    String websiteUrl,
    String vcsUrl,
    List<String> topics
) {
    public RepositoryDetails withAdditionalTopic(String addTopic) {
        return new RepositoryDetails(
            name,
            version,
            description,
            websiteUrl,
            vcsUrl,
            new ArrayList<>(topics) {{
                add(addTopic);
            }}
        );
    }
}
