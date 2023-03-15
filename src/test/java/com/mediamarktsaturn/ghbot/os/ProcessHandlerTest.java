package com.mediamarktsaturn.ghbot.os;

import static com.mediamarktsaturn.ghbot.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ProcessHandlerTest {

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
            assertThat(failure.cause().getCause()).hasToString("java.io.IOException: Cannot run program \"moep\" (in directory \".\"): error=2, No such file or directory");
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
