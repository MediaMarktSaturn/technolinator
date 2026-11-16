package com.mediamarktsaturn.technolinator.housekeeping;

import org.jboss.logging.MDC;

import java.time.Instant;

/**
 * Describes a single execution of the housekeeping, shared by all tasks taking part in it.
 *
 * @param id        Short identifier of the run, also used as trace id in the logs
 * @param startedAt Start of the run
 * @param dryRun    If set, tasks must not change anything (neither in GitHub nor in Dependency-Track), but only log what they would do
 */
public record HousekeepingRun(
    String id,
    Instant startedAt,
    boolean dryRun
) {

    /**
     * Replaces the MDC of the current thread with the trace id of this run.
     * Worker threads are shared with the event handling, so remainders of other processes need to be removed.
     */
    public void writeToMDC() {
        MDC.clear();
        MDC.put("traceId", id);
    }
}
