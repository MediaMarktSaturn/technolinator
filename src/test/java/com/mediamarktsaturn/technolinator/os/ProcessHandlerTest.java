package com.mediamarktsaturn.technolinator.os;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ProcessHandlerTest {

    @Test
    void testLogResult() {
        // Given
        var command = "ls -a";

        // When
        var result = await(ProcessHandler.run(command));

        // Then
        assertThat(result).isInstanceOfSatisfying(ProcessHandler.ProcessResult.Success.class, success -> {
            assertThat(success.outputLines()).contains("pom.xml", "src", "README.md", ".gitignore", ".editorconfig");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ls",
        "ls -l  ",
        " ls -lah",
        "ls -l  -a",
        "  ls  -l -a -h "
    })
    void testSuccessfulCommandWithDefaults(String command) {
        // When
        var result = await(ProcessHandler.run(command));

        // Then
        assertThat(result).isInstanceOf(ProcessHandler.ProcessResult.Success.class);
    }

    @Test
    void testFailingCommandWithDefaults() {
        // Given
        String command = "moep";

        // When
        var result = await(ProcessHandler.run(command));

        // Then
        assertThat(result).isInstanceOfSatisfying(ProcessHandler.ProcessResult.Failure.class, failure -> {
            assertThat(failure.cause().getCause()).isInstanceOf(java.io.IOException.class).hasMessageContaining("Cannot run program \"moep\" (in directory \".\")");
        });
    }

    @Test
    void testSuccessfulEnv() {
        // Given
        var env = Map.of("TEST", "ls -a");
        var command = "bash -c $TEST";

        // When
        var result = await(ProcessHandler.run(command, ProcessHandler.CURRENT_DIR, env, ProcessCallback.NOOP));

        // Then
        assertThat(result).isInstanceOfSatisfying(ProcessHandler.ProcessResult.Success.class, success -> {
            assertThat(success.outputLines()).contains("pom.xml", "src", "README.md", ".gitignore", ".editorconfig");
        });
    }

    @Test
    void testFailingEnv() {
        // Given
        var env = Map.of("TEST", "zonk");
        var command = "bash -c $TEST";

        // When
        var result = await(ProcessHandler.run(command, ProcessHandler.CURRENT_DIR, env, ProcessCallback.NOOP));

        // Then
        assertThat(result).isInstanceOfSatisfying(ProcessHandler.ProcessResult.Failure.class, failure -> {
            assertThat(failure.exitCode()).isEqualTo(127);
        });
    }
}
