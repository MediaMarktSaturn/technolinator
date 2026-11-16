package com.mediamarktsaturn.technolinator.housekeeping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryReferenceTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://github.com/Org/Repo",
        "https://github.com/org/repo/",
        "git://github.com/org/repo.git",
        "https://github.com/org/repo.git",
        "https://GitHub.com/org/repo/releases",
        " https://github.com/org/repo "
    })
    void parsesDifferentNotationsOfTheSameRepository(String url) {
        assertThat(RepositoryReference.parse(url)).contains(new RepositoryReference("github.com", "org", "repo"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        " ",
        "https://github.com",
        "https://github.com/org",
        "https://github.com/org/.git",
        "link://to.nowhere",
        "nope",
        "git@github.com:org/repo.git",
        "https://github.com/or g/repo"
    })
    void ignoresUrlsNotDenotingARepository(String url) {
        assertThat(RepositoryReference.parse(url)).isEmpty();
    }

    @Test
    void ignoresNull() {
        assertThat(RepositoryReference.parse(null)).isEmpty();
    }

    @Test
    void distinguishesHosts() {
        assertThat(RepositoryReference.parse("https://ghe.example.com/org/repo"))
            .isNotEqualTo(RepositoryReference.parse("https://github.com/org/repo"));
    }
}
