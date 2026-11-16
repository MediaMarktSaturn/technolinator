package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SecretRedactorTest {

    private final SecretRedactor cut = new SecretRedactor(List.of("s3cr3t-value-123", "short"));

    @Test
    void knownSecretValuesAreRedactedAnywhere() {
        assertThat(cut.redact("curl -u user:s3cr3t-value-123 https://x")).doesNotContain("s3cr3t-value-123").contains(SecretRedactor.MASK);
    }

    @Test
    void tooShortSecretsAreNotUsed() {
        assertThat(cut.redact("a short text")).isEqualTo("a short text");
    }

    @Test
    void urlCredentials() {
        assertThat(cut.redact("fetching https://bob:hunter2@repo.example.com/maven2"))
            .doesNotContain("hunter2").contains("https://" + SecretRedactor.MASK + "@repo.example.com");
    }

    @Test
    void authorizationHeaders() {
        assertThat(cut.redact("Authorization: Bearer abcdef.ghijkl")).doesNotContain("abcdef");
        assertThat(cut.redact("authorization=Basic dXNlcjpwYXNz")).doesNotContain("dXNlcjpwYXNz");
    }

    @Test
    void keyValuePairs() {
        assertThat(cut.redact("-Dmaven.password=topsecret -DapiKey: foo123")).doesNotContain("topsecret").doesNotContain("foo123");
        assertThat(cut.redact("//registry.npmjs.org/:_authToken=npm_abc123")).doesNotContain("npm_abc123");
    }

    @Test
    void githubTokensAndJwts() {
        assertThat(cut.redact("token ghp_abcdefghijklmnopqrstuvwxyz0123456789")).doesNotContain("ghp_abcdef");
        assertThat(cut.redact("github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuv")).doesNotContain("github_pat_11");
        assertThat(cut.redact("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N")).doesNotContain("eyJhbGci");
    }

    @Test
    void privateKeyBlocksAreRemoved() {
        var lines = List.of("before", "-----BEGIN RSA PRIVATE KEY-----", "MIIEowIBAAKCAQEA", "abcdef", "-----END RSA PRIVATE KEY-----", "after");

        assertThat(cut.redactLines(lines)).containsExactly("before", SecretRedactor.MASK + " (private key)", "after");
    }

    @Test
    void quotedValuesAndJsonKeys() {
        assertThat(cut.redact("{\"password\": \"hunter2 with spaces\", \"user\": \"bob\"}")).doesNotContain("hunter2").doesNotContain("spaces").contains("bob");
        assertThat(cut.redact("password: 'my secret phrase'")).doesNotContain("phrase");
        assertThat(cut.redact("{\"Authorization\": \"Bearer abcdef123456\"}")).doesNotContain("abcdef123456");
    }

    @Test
    void urlPasswordsMayContainAtSigns() {
        assertThat(cut.redact("https://bob:p@ssw0rd@repo.example.com/maven2")).doesNotContain("ssw0rd").contains("@repo.example.com/maven2");
    }

    @Test
    void tokensOfOtherServices() {
        assertThat(cut.redact("AKIAIOSFODNN7EXAMPLE")).doesNotContain("AKIA");
        assertThat(cut.redact("glpat-abcdefghijklmnopqrstuvwx")).doesNotContain("glpat-");
        assertThat(cut.redact("sent Bearer abcdefghijklmnop1234 to server")).doesNotContain("abcdefghijklmnop1234");
        assertThat(cut.redact("prefix_ghp_abcdefghijklmnopqrstuvwxyz0123456789")).doesNotContain("ghp_abcdef");
    }

    @Test
    void privateKeyBlocksOfPgpAreRemoved() {
        var lines = List.of("-----BEGIN PGP PRIVATE KEY BLOCK-----", "lQdGBF", "-----END PGP PRIVATE KEY BLOCK-----", "after");

        assertThat(cut.redactLines(lines)).containsExactly(SecretRedactor.MASK + " (private key)", "after");
    }

    @Test
    void hugeUntrustedInputIsRedactedInLinearTime() {
        var evil = String.join(".", java.util.Collections.nCopies(200_000, "a-b")) + " " + "https://".repeat(50_000) + "x".repeat(500_000);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> cut.redact(evil));
    }

    @Test
    void harmlessTextStaysUntouched() {
        var text = "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile";

        assertThat(cut.redact(text)).isEqualTo(text);
    }

    @Test
    void multiLineSecretsAreRedactedLineByLine() {
        var redactor = new SecretRedactor(List.of("-----BEGIN KEY-----\nabcdefghijklmnop\nqrstuvwxyz012345"));

        assertThat(redactor.redact("log: abcdefghijklmnop")).doesNotContain("abcdefghijklmnop");
    }
}
