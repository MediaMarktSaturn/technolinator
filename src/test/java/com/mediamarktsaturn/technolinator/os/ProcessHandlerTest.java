package com.mediamarktsaturn.technolinator.os;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.mediamarktsaturn.technolinator.TestUtil.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
    void testCancellationKillsProcess() {
        // Given
        var command = "sleep 4711";

        // When
        var subscription = ProcessHandler.run(command, ProcessHandler.CURRENT_DIR, Map.of(), ProcessCallback.NOOP)
            .subscribe().with(ignored -> {
            }, failure -> {
            });
        await().atMost(Duration.ofSeconds(10)).until(() -> sleepProcessAlive());
        subscription.cancel();

        // Then
        await().atMost(Duration.ofSeconds(10)).until(() -> !sleepProcessAlive());
    }

    private static boolean sleepProcessAlive() {
        return ProcessHandle.allProcesses().anyMatch(p -> p.isAlive() && p.info().commandLine().filter(cmd -> cmd.endsWith("sleep 4711")).isPresent());
    }

    @Test
    void testIsolatedEnvDoesNotInheritCurrentEnv() {
        // Given
        var env = Map.of("TEST", "explicit");

        // When
        var result = await(ProcessHandler.runIsolated("/usr/bin/env", ProcessHandler.CURRENT_DIR, env, ProcessCallback.NOOP));

        // Then
        assertThat(result).isInstanceOfSatisfying(ProcessHandler.ProcessResult.Success.class, success -> {
            assertThat(success.outputLines()).containsExactly("TEST=explicit");
        });
    }

    @Test
    void testIsolatedArgumentListPreservesSpaces() {
        var result = await(ProcessHandler.runIsolated(List.of("/bin/echo", "two words"),
            ProcessHandler.CURRENT_DIR, Map.of()));
        assertThat(result).isInstanceOfSatisfying(ProcessHandler.ProcessResult.Success.class,
            success -> assertThat(success.outputLines()).containsExactly("two words"));
    }

    @Test
    void testFailedProcessOutputIsReportedCompletely() {
        // Given
        var recorder = new RecordingCallback();

        // When
        var result = await(ProcessHandler.run("ls /does/not/exist", ProcessHandler.CURRENT_DIR, Map.of(), recorder));

        // Then
        assertThat(result).isInstanceOfSatisfying(ProcessHandler.ProcessResult.Failure.class, failure -> {
            assertThat(failure.outputLines()).isNotEmpty();
            assertThat(recorder.failureOutput).isEqualTo(failure.outputLines());
        });
    }

    @Test
    void testSuccessfulProcessOutputIsNotReportedAsFailure() {
        // Given
        var recorder = new RecordingCallback();

        // When
        await(ProcessHandler.run("ls", ProcessHandler.CURRENT_DIR, Map.of(), recorder));

        // Then
        assertThat(recorder.failureOutput).isNull();
    }

    private static class RecordingCallback implements ProcessCallback {
        volatile List<String> failureOutput;

        @Override
        public void onComplete(int exitStatus) {
        }

        @Override
        public void onOutput(String logLine) {
        }

        @Override
        public void onFailure(Throwable failure) {
        }

        @Override
        public void log(String message) {
        }

        @Override
        public void onFailureOutput(List<String> outputLines) {
            failureOutput = outputLines;
        }
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
