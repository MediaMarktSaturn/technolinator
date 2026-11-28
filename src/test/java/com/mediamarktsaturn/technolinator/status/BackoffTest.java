package com.mediamarktsaturn.technolinator.status;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    private static final Duration INITIAL = Duration.ofMinutes(5);
    private static final Duration MAX = Duration.ofHours(4);

    @Test
    void doublesTheDelayOfEveryAttempt() {
        assertThat(Backoff.delay(0, INITIAL, MAX)).isEqualTo(Duration.ofMinutes(5));
        assertThat(Backoff.delay(1, INITIAL, MAX)).isEqualTo(Duration.ofMinutes(10));
        assertThat(Backoff.delay(2, INITIAL, MAX)).isEqualTo(Duration.ofMinutes(20));
        assertThat(Backoff.delay(3, INITIAL, MAX)).isEqualTo(Duration.ofMinutes(40));
        assertThat(Backoff.delay(4, INITIAL, MAX)).isEqualTo(Duration.ofMinutes(80));
        assertThat(Backoff.delay(5, INITIAL, MAX)).isEqualTo(Duration.ofMinutes(160));
    }

    @Test
    void neverExceedsTheMaximum() {
        assertThat(Backoff.delay(6, INITIAL, MAX)).isEqualTo(MAX);
        assertThat(Backoff.delay(7, INITIAL, MAX)).isEqualTo(MAX);
        assertThat(Backoff.delay(10_000, INITIAL, MAX)).isEqualTo(MAX);
    }

    @Test
    void initialDelayAboveTheMaximumIsLimited() {
        assertThat(Backoff.delay(0, Duration.ofHours(8), MAX)).isEqualTo(MAX);
    }
}
