package com.mediamarktsaturn.technolinator.housekeeping;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RunBudgetTest {

    @Test
    void reservesWhatFitsCompletely() {
        var budget = new RunBudget("projects", 3);

        assertThat(budget.tryReserve(2, "first")).isTrue();
        assertThat(budget.tryReserve(2, "second")).isFalse();
        assertThat(budget.tryReserve(1, "third")).isTrue();
        assertThat(budget.tryReserve(1, "fourth")).isFalse();
        assertThat(budget.deferred()).isEqualTo(3);
    }

    @Test
    void neverReservesMoreThanTheLimitForASingleUnit() {
        var budget = new RunBudget("projects", 2);

        assertThat(budget.tryReserve(3, "big")).isFalse();
        assertThat(budget.tryReserve(2, "small")).isTrue();
    }

    @Test
    void reservesNothingWithoutLimit() {
        var budget = new RunBudget("projects", 0);

        assertThat(budget.tryReserve(1, "unit")).isFalse();
        assertThat(budget.tryReserve(0, "empty")).isTrue();
        assertThat(budget.reserveUpTo(1, "unit")).isZero();
    }

    @Test
    void reservesPartially() {
        var budget = new RunBudget("projects", 3);

        assertThat(budget.reserveUpTo(2, "first")).isEqualTo(2);
        assertThat(budget.reserveUpTo(2, "second")).isEqualTo(1);
        assertThat(budget.reserveUpTo(2, "third")).isZero();
        assertThat(budget.deferred()).isEqualTo(3);
    }

    @Test
    void releasesReservations() {
        var budget = new RunBudget("projects", 2);
        assertThat(budget.tryReserve(2, "first")).isTrue();

        budget.release(2);

        assertThat(budget.tryReserve(2, "second")).isTrue();
    }

    @Test
    void isNeverExceededConcurrently() {
        var budget = new RunBudget("projects", 100);
        var reserved = new AtomicInteger();

        IntStream.range(0, 1000).parallel().forEach(i -> {
            if (budget.tryReserve(3, "unit " + i)) {
                reserved.addAndGet(3);
            }
            reserved.addAndGet(budget.reserveUpTo(2, "unit " + i));
        });

        assertThat(reserved.get()).isEqualTo(100);
    }
}
