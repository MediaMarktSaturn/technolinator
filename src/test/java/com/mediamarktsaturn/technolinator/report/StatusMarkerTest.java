package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StatusMarkerTest {

    private static final Instant STARTED = Instant.parse("2026-03-01T10:15:30Z");

    @Test
    void roundTrip() {
        var marker = new StatusMarker(StatusMarker.State.FAILED, STARTED, "abc123", "1a2b3c4d");

        var parsed = StatusMarker.parse("Some text\n" + marker.render() + "\nmore");

        assertThat(parsed).contains(marker);
    }

    @Test
    void emptyValuesAreRenderedAsDash() {
        var marker = new StatusMarker(StatusMarker.State.RECOVERED, STARTED, "", null);

        assertThat(marker.render()).contains("sha=-").contains("err=-").contains("state=recovered");
        assertThat(StatusMarker.parse(marker.render())).hasValueSatisfying(m -> {
            assertThat(m.state()).isEqualTo(StatusMarker.State.RECOVERED);
            assertThat(m.sha()).isEqualTo("-");
        });
    }

    @Test
    void malformedMarkersAreIgnored() {
        assertThat(StatusMarker.parse(null)).isEmpty();
        assertThat(StatusMarker.parse("no marker")).isEmpty();
        assertThat(StatusMarker.parse("<!-- technolinator:status v=1 state=bogus started=x -->")).isEmpty();
        assertThat(StatusMarker.isPresentIn("<!-- technolinator:status v=1 state=bogus started=x -->")).isTrue();
    }

    @Test
    void replaceExchangesOnlyTheMarker() {
        var failed = new StatusMarker(StatusMarker.State.FAILED, STARTED, "abc", "def");
        var resolved = new StatusMarker(StatusMarker.State.RESOLVED, STARTED, "abc", "def");

        var result = StatusMarker.replace("before " + failed.render() + " after", resolved);

        assertThat(result).isEqualTo("before " + resolved.render() + " after");
    }
}
