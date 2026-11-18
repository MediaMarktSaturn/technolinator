package com.mediamarktsaturn.technolinator.os;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class DefaultProcessCallbackTest {

    private static final String LOGGER = ProcessCallback.DefaultProcessCallback.class.getName();

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void attach() {
        Logger.getLogger(LOGGER).addHandler(handler);
    }

    @AfterEach
    void detach() {
        Logger.getLogger(LOGGER).removeHandler(handler);
    }

    @Test
    void logsCompleteOutputOfFailedProcessAsWarning() {
        // Given
        var cut = new ProcessCallback.DefaultProcessCallback();
        var lines = List.of("first", "second", "third", "fourth", "fifth");

        // When
        cut.onFailureOutput(lines);

        // Then
        var warnings = records.stream().filter(r -> r.getLevel().intValue() == Level.WARNING.intValue()).toList();
        assertThat(warnings).hasSize(lines.size() + 1);
        assertThat(warnings).extracting(r -> r.getMessage() + " " + String.join(" ", java.util.Arrays.stream(r.getParameters() == null ? new Object[0] : r.getParameters()).map(String::valueOf).toList()))
            .anySatisfy(msg -> assertThat(msg).contains("first"))
            .anySatisfy(msg -> assertThat(msg).contains("fifth"));
    }

    @Test
    void logsNothingWithoutOutput() {
        // When
        new ProcessCallback.DefaultProcessCallback().onFailureOutput(List.of());

        // Then
        assertThat(records).isEmpty();
    }
}
