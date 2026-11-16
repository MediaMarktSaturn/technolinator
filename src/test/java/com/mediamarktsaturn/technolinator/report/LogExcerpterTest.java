package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogExcerpterTest {

    @Test
    void ansiAndControlCharactersAreRemoved() {
        var lines = LogExcerpter.cleanLines(List.of("\u001B[31mERROR\u001B[0m something \u0007bad", "\u001B]0;title\u0007plain"));

        assertThat(lines).containsExactly("ERROR something bad", "plain");
    }

    @Test
    void progressRewritesKeepTheLastState() {
        assertThat(LogExcerpter.cleanLines(List.of("10%\r50%\r100% done"))).containsExactly("100% done");
    }

    @Test
    void repeatedLinesAreFolded() {
        var lines = LogExcerpter.cleanLines(List.of("a", "a", "a", "a", "b"));

        assertThat(lines).containsExactly("a", "[... previous line repeated 3 more times]", "b");
    }

    @Test
    void longLinesAreCut() {
        var lines = LogExcerpter.cleanLines(List.of("x".repeat(5000)));

        assertThat(lines.getFirst()).hasSizeLessThan(500).endsWith("[...]");
    }

    @Test
    void firstErrorLine() {
        assertThat(LogExcerpter.firstErrorLine(List.of("info", "  [ERROR] boom ", "error: later"))).isEqualTo("[ERROR] boom");
        assertThat(LogExcerpter.firstErrorLine(List.of("all fine"))).isEmpty();
    }

    @Test
    void firstErrorLineIndexIsTheIndexInTheGivenLines() {
        assertThat(LogExcerpter.firstErrorLineIndex(List.of("info", "  [ERROR] boom "))).isEqualTo(1);
        assertThat(LogExcerpter.firstErrorLineIndex(List.of("all fine"))).isEqualTo(-1);
    }

    @Test
    void secretsAreRedactedBeforeLongLinesAreCut() {
        var redactor = new SecretRedactor(List.of("SECRET-VALUE-1234"));
        var line = "x".repeat(LogExcerpter.MAX_LINE_LENGTH - 5) + "SECRET-VALUE-1234";

        var lines = LogExcerpter.cleanLines(List.of(line), redactor::redactLines);

        assertThat(lines.getFirst()).doesNotContain("SECRE").contains("[REDA");
    }

    @Test
    void hugeLogIsReducedToTheLimitAndKeepsTail() {
        var lines = new ArrayList<String>();
        for (var i = 0; i < 100_000; i++) {
            lines.add("line number " + i + " " + "x".repeat(40));
        }
        lines.add("BUILD FAILURE at the very end");
        var cleaned = LogExcerpter.cleanLines(lines);

        var excerpt = LogExcerpter.excerpt(cleaned, 10_000);

        assertThat(excerpt).hasSizeLessThanOrEqualTo(10_000).contains("BUILD FAILURE at the very end");
    }

    @Test
    void errorFarFromTheEndIsKeptWithContext() {
        var lines = new ArrayList<String>();
        for (var i = 0; i < 2_000; i++) {
            lines.add("noise " + i);
        }
        lines.set(500, "java.lang.IllegalStateException: the real cause");
        lines.set(499, "context before");
        lines.set(501, "context after");

        var excerpt = LogExcerpter.excerpt(lines, 20_000);

        assertThat(excerpt).contains("the real cause", "context before", "context after").contains("noise 1999").doesNotContain("noise 100\n");
    }

    @Test
    void emptyInput() {
        assertThat(LogExcerpter.excerpt(List.of(), 1000)).isEmpty();
    }

    @Test
    void fitKeepsTheEnd() {
        var text = "first\n" + "middle\n".repeat(100) + "last";

        var fitted = LogExcerpter.fit(text, 100);

        assertThat(fitted).hasSizeLessThanOrEqualTo(100).startsWith("[... truncated").endsWith("last");
        assertThat(LogExcerpter.fit("short", 100)).isEqualTo("short");
    }
}
