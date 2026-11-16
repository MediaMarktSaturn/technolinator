package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BotIdentityTest {

    @Test
    void onlyTheKnownAppIsTakenForTheApp() {
        var app = Optional.of("technolinator[bot]");

        assertThat(BotIdentity.isWrittenByApp("technolinator[bot]", app)).isTrue();
        assertThat(BotIdentity.isWrittenByApp("Technolinator[bot]", app)).isTrue();
        assertThat(BotIdentity.isWrittenByApp("dependabot[bot]", app)).isFalse();
        assertThat(BotIdentity.isWrittenByApp("mallory", app)).isFalse();
        assertThat(BotIdentity.isWrittenByApp(null, app)).isFalse();
    }

    @Test
    void withoutKnownAppAnyBotButNoPersonIsTakenForTheApp() {
        assertThat(BotIdentity.isWrittenByApp("technolinator[bot]", Optional.empty())).isTrue();
        assertThat(BotIdentity.isWrittenByApp("mallory", Optional.empty())).isFalse();
        assertThat(BotIdentity.isWrittenByApp(null, Optional.empty())).isFalse();
    }
}
