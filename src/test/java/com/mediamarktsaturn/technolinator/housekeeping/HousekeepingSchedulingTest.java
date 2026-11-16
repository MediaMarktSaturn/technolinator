package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HousekeepingSchedulingTest {

    @Inject
    Scheduler scheduler;

    @Inject
    HousekeepingService service;

    @Test
    void housekeepingJobIsScheduled() {
        assertThat(scheduler.getScheduledJob("housekeeping")).isNotNull();
    }

    @Test
    void runWithoutTasksDoesNothing() {
        service.runAll().await().atMost(Duration.ofSeconds(10));
    }
}
