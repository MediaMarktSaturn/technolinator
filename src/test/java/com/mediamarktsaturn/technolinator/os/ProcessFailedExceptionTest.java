package com.mediamarktsaturn.technolinator.os;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessFailedExceptionTest {

    @Test
    void failingProcessCarriesItsOutputAndExitCode() {
        var result = ProcessHandler.run("ls /this/directory/does/not/exist").await().atMost(Duration.ofSeconds(30));

        assertThat(result).isInstanceOfSatisfying(ProcessHandler.ProcessResult.Failure.class, failure -> {
            assertThat(failure.exitCode()).isNotZero();
            assertThat(failure.cause()).isInstanceOfSatisfying(ProcessFailedException.class, cause -> {
                assertThat(cause.exitCode()).isEqualTo(failure.exitCode());
                assertThat(cause.command()).isEqualTo("ls /this/directory/does/not/exist");
                assertThat(String.join("\n", cause.outputLines())).contains("/this/directory/does/not/exist");
                assertThat(cause.projectName()).isNull();
                // the output is for reports, not for logs
                assertThat(cause.getMessage()).isEqualTo("ls /this/directory/does/not/exist exited with " + failure.exitCode());
            });
        });
    }

    @Test
    void projectNameAndLocationCanBeAttached() {
        var named = new ProcessFailedException("cdxgen", 1, List.of("a")).withProject("backend", "services/backend");

        assertThat(named.projectName()).isEqualTo("backend");
        assertThat(named.location()).isEqualTo("services/backend");
        assertThat(named.withProjectName("other").location()).isEqualTo("services/backend");
        assertThat(new ProcessFailedException("cdxgen", 1, List.of()).location()).isNull();
    }

    @Test
    void projectNameCanBeAttached() {
        var original = new ProcessFailedException("cdxgen", 1, List.of("a"));

        var named = original.withProjectName("backend");

        assertThat(named.projectName()).isEqualTo("backend");
        assertThat(named.command()).isEqualTo("cdxgen");
        assertThat(named.outputLines()).containsExactly("a");
        assertThat(named.exitCode()).isEqualTo(1);
    }

    @Test
    void outputIsCopied() {
        var lines = new ArrayList<>(List.of("a"));

        var cut = new ProcessFailedException("cdxgen", 1, lines);
        lines.add("b");

        assertThat(cut.outputLines()).containsExactly("a");
    }
}
