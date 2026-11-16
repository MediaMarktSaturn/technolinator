package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Triggers the {@link HousekeepingService} by cron expression ({@code app.housekeeping.cron}, by default nightly).
 * Scheduling happens in-app, which is sufficient as long as Technolinator runs as a single replica.
 */
@ApplicationScoped
public class HousekeepingScheduler {

    private final HousekeepingService service;
    private final boolean enabled;

    public HousekeepingScheduler(
        HousekeepingService service,
        @ConfigProperty(name = "app.housekeeping.enabled") boolean enabled
    ) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(identity = "housekeeping", cron = "{app.housekeeping.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    Uni<Void> scheduledHousekeeping() {
        if (!enabled) {
            Log.debug("Housekeeping is disabled");
            return Uni.createFrom().voidItem();
        }
        return service.runAll();
    }
}
