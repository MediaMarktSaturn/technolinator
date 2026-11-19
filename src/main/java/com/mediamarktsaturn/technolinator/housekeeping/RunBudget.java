package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkus.logging.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Upper limit of the projects an action changes during a run ({@code max-per-run}), as protection against mass changes caused by faulty data.
 * <p>
 * The limit is strict: units are reserved for as a whole before anything is changed, and a unit that doesn't fit into what's left is left
 * for the next run. So not even a single big unit exceeds it; a unit needing more than the limit allows at all is never processed, which
 * is reported, so the limit can be raised deliberately.
 * Thread-safe, as units are processed in parallel.
 */
final class RunBudget {

    private final String subject;
    private final int max;
    private final AtomicInteger used = new AtomicInteger();
    private final AtomicInteger deferred = new AtomicInteger();
    private final AtomicBoolean exhaustionReported = new AtomicBoolean();

    /**
     * @param subject What's counted, for log messages, e.g. "archived projects"
     * @param max     Maximum per run
     */
    RunBudget(String subject, int max) {
        this.subject = subject;
        this.max = Math.max(0, max);
    }

    /**
     * Reserves [count] projects of the [unit] (for log messages), if they fit completely
     *
     * @return {@code false}, if they don't fit, then nothing is reserved
     */
    boolean tryReserve(int count, String unit) {
        if (count <= 0) {
            return true;
        }
        int before;
        do {
            before = used.get();
            if (before + count > max) {
                deferred.addAndGet(count);
                report(count, unit);
                return false;
            }
        } while (!used.compareAndSet(before, before + count));
        return true;
    }

    /**
     * Reserves as many of [count] projects of the [unit] (for log messages) as fit
     *
     * @return the number of projects reserved, between 0 and [count]
     */
    int reserveUpTo(int count, String unit) {
        if (count <= 0) {
            return 0;
        }
        int before;
        int granted;
        do {
            before = used.get();
            granted = Math.max(0, Math.min(count, max - before));
        } while (granted > 0 && !used.compareAndSet(before, before + granted));
        if (granted < count) {
            deferred.addAndGet(count - granted);
            reportExhausted(unit);
        }
        return granted;
    }

    /**
     * Gives back [count] reserved projects, that weren't changed after all
     */
    void release(int count) {
        if (count > 0) {
            used.updateAndGet(current -> Math.max(0, current - count));
        }
    }

    /**
     * @return the number of projects left for the next run, because they didn't fit
     */
    int deferred() {
        return deferred.get();
    }

    int max() {
        return max;
    }

    String subject() {
        return subject;
    }

    private void report(int count, String unit) {
        if (count > max) {
            Log.warnf("Leaving %s alone, it needs %d %s, which is more than the limit of %d per run allows; raise the limit to have it processed",
                unit, count, subject, max);
        } else {
            reportExhausted(unit);
        }
    }

    private void reportExhausted(String unit) {
        if (!exhaustionReported.getAndSet(true)) {
            Log.warnf("Limit of %d %s per run reached, leaving %s and further ones for the next run", max, subject, unit);
        } else {
            Log.debugf("Limit of %d %s per run reached, leaving %s for the next run", max, subject, unit);
        }
    }
}
