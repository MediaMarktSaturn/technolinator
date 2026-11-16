package com.mediamarktsaturn.technolinator.report;

import com.mediamarktsaturn.technolinator.os.ProcessFailedException;
import org.cyclonedx.exception.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    private static FailureClassifier.Classification classify(Integer exit, String... lines) {
        return FailureClassifier.classify(FailureStage.SBOM_GENERATION, new ProcessFailedException("cdxgen -o bom.json", exit, List.of(lines)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        [ERROR] Failed to execute goal on project foo: Could not resolve dependencies for project foo:jar:1.0|maven|REPO_ACTIONABLE
        > Could not resolve all files for configuration ':app:runtimeClasspath'.|gradle|REPO_ACTIONABLE
        npm ERR! code E404|npm|REPO_ACTIONABLE
        npm error code ERESOLVE|npm|REPO_ACTIONABLE
        go: github.com/foo/bar@v1.2.3: missing go.sum entry for module providing package|go|REPO_ACTIONABLE
        ERROR: No matching distribution found for requests==99|pip|REPO_ACTIONABLE
        error: failed to select a version for the requirement `serde = "^9"`|cargo|REPO_ACTIONABLE
        error NU1101: Unable to find package Foo.Bar|dotnet|REPO_ACTIONABLE
        FAILURE: Build failed with an exception.|gradle|REPO_ACTIONABLE
        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile|maven|REPO_ACTIONABLE
        """)
    void toolSpecificSignatures(String line, String tool, FailureKind kind) {
        var result = classify(1, "Starting cdxgen", line);

        assertThat(result.tool()).isEqualTo(tool);
        assertThat(result.kind()).isEqualTo(kind);
        assertThat(result.errorLine()).isNotBlank();
        assertThat(result.summary()).isNotBlank();
        assertThat(result.errorHash()).hasSize(8);
    }

    @Test
    void outOfMemory() {
        var result = classify(1, "[INFO] building", "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space");

        assertThat(result.summary()).contains("memory");
        assertThat(result.kind()).isEqualTo(FailureKind.REPO_ACTIONABLE);
        assertThat(result.tool()).isEqualTo("maven");
    }

    @Test
    void jdkMismatch() {
        var result = classify(1, "gradle build", "Unsupported class file major version 69");

        assertThat(result.summary()).contains("JDK");
        assertThat(result.hint()).contains("jdk.version");
        assertThat(result.tool()).isEqualTo("gradle");
    }

    @Test
    void credentials() {
        var result = classify(1, "[INFO] mvn", "Could not transfer artifact foo:bar:pom:1 from/to private (https://x): status code: 401, reason phrase: Unauthorized");

        assertThat(result.summary()).contains("denied access");
        assertThat(result.hint()).contains("credentials");
    }

    @Test
    void timeoutInOutputIsATimeout() {
        var result = classify(1, "npm info", "network request timed out");

        assertThat(result.kind()).isEqualTo(FailureKind.TIMEOUT);
    }

    @Test
    void mostSpecificSignatureWinsOverEarlierLines() {
        var result = classify(1, "[ERROR] Failed to execute goal x", "Caused by: java.lang.OutOfMemoryError");

        assertThat(result.summary()).contains("memory");
    }

    @Test
    void exit137WithoutOtherHint() {
        var result = classify(137, "Starting", "Killed");

        assertThat(result.summary()).contains("137");
        assertThat(result.kind()).isEqualTo(FailureKind.REPO_ACTIONABLE);
    }

    @Test
    void unknownFailureFallsBackToCdxgen() {
        var result = classify(2, "something happened", "bye");

        assertThat(result.tool()).isEqualTo("cdxgen");
        assertThat(result.summary()).contains("exit code 2");
        assertThat(result.kind()).isEqualTo(FailureKind.REPO_ACTIONABLE);
    }

    @Test
    void errorLineIsFoundDespiteIndentation() {
        var result = classify(1, "[INFO] mvn run", "   something error happened");

        assertThat(result.errorLine()).isEqualTo("something error happened");
        assertThat(result.tool()).isEqualTo("maven");
    }

    @Test
    void missingExitCodeIsNotShown() {
        var result = classify(null, "something happened");

        assertThat(result.summary()).isEqualTo("cdxgen failed");
    }

    @Test
    void secretsAreRedactedBeforeTheyAreShownOrHashed() {
        var redactor = new SecretRedactor(List.of());
        var first = classifyRedacted(redactor, "[ERROR] unknown failure token=aaaaaaaa1");
        var second = classifyRedacted(redactor, "[ERROR] unknown failure token=bbbbbbbb2");

        assertThat(first.errorLine()).doesNotContain("aaaaaaaa1").contains(SecretRedactor.MASK);
        assertThat(first.errorHash()).isEqualTo(second.errorHash());
    }

    private static FailureClassifier.Classification classifyRedacted(SecretRedactor redactor, String... lines) {
        return FailureClassifier.classify(FailureStage.SBOM_GENERATION,
            new ProcessFailedException("cdxgen -o bom.json", 1, List.of(lines)), redactor::redactLines);
    }

    @Test
    void hashIgnoresNumbersAndHashes() {
        var first = classify(1, "[ERROR] Could not resolve dependencies at 1234 abcdef123456 for foo");
        var second = classify(1, "[ERROR] Could not resolve dependencies at 99 fedcba654321 for foo");
        var other = classify(1, "[ERROR] Could not resolve dependencies at 99 for bar");

        assertThat(first.errorHash()).isEqualTo(second.errorHash()).isNotEqualTo(other.errorHash());
    }

    @Test
    void ansiOutputIsClassified() {
        var result = classify(1, "\u001B[31m[ERROR]\u001B[0m Could not resolve dependencies for project");

        assertThat(result.tool()).isEqualTo("maven");
    }

    @Test
    void checkoutAndUploadFailuresAreInfrastructure() {
        assertThat(FailureClassifier.classify(FailureStage.CHECKOUT, new IOException("boom")).kind()).isEqualTo(FailureKind.INFRASTRUCTURE);
        assertThat(FailureClassifier.classify(FailureStage.UPLOAD, new IOException("boom")).kind()).isEqualTo(FailureKind.INFRASTRUCTURE);
    }

    @Test
    void unknownExceptionsAreInfrastructure() {
        var result = FailureClassifier.classify(FailureStage.SBOM_GENERATION, new IllegalStateException("bug"));

        assertThat(result.kind()).isEqualTo(FailureKind.INFRASTRUCTURE);
    }

    @Test
    void unreadableSbomIsActionable() {
        var result = FailureClassifier.classify(FailureStage.SBOM_GENERATION, new ParseException("invalid json"));

        assertThat(result.kind()).isEqualTo(FailureKind.REPO_ACTIONABLE);
        assertThat(result.tool()).isEqualTo("cdxgen");
    }

    @Test
    void mutinyTimeoutIsATimeout() {
        var result = FailureClassifier.classify(FailureStage.SBOM_GENERATION, new io.smallrye.mutiny.TimeoutException());

        assertThat(result.kind()).isEqualTo(FailureKind.TIMEOUT);
    }
}
