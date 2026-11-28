package com.mediamarktsaturn.technolinator.status;

import java.time.Duration;

/**
 * Exponential backoff: every attempt waits twice as long as the one before, up to a maximum.
 */
public final class Backoff {

    private Backoff() {
    }

    /**
     * @param attempt the number of the attempt, starting with 0
     * @return the time to wait before the attempt: [initial] for the first one, doubled for each of the following, but not more than [max]
     */
    public static Duration delay(int attempt, Duration initial, Duration max) {
        var delay = initial;
        for (var i = 0; i < attempt && delay.compareTo(max) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(max) < 0 ? delay : max;
    }
}
