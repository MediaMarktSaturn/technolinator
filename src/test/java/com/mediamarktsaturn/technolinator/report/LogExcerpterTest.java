package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
    void logsWhichLoseFewLinesAreShownCompletely() {
        var lines = numberedLines(120);

        var excerpt = LogExcerpter.excerpt(lines, 20_000);

        assertThat(excerpt).isEqualTo(String.join("\n", lines)).doesNotContain("omitted");
    }

    @Test
    void logsWhichLoseManyLinesAreCondensed() {
        var lines = numberedLines(150);

        var excerpt = LogExcerpter.excerpt(lines, 20_000);

        assertThat(excerpt).contains("[... 70 lines omitted ...]").contains("noise 149").doesNotContain("noise 0\n").doesNotContain("noise 69\n");
    }

    @Test
    void logsWhichLoseFewLinesStayWithinTheCharacterLimit() {
        var lines = new ArrayList<String>();
        for (var i = 0; i < 120; i++) {
            lines.add("noise " + i + " " + "x".repeat(300));
        }

        var excerpt = LogExcerpter.excerpt(lines, 10_000);

        assertThat(excerpt).hasSizeLessThanOrEqualTo(10_000).contains("noise 119");
    }

    private static List<String> numberedLines(int count) {
        var lines = new ArrayList<String>();
        for (var i = 0; i < count; i++) {
            lines.add("noise " + i);
        }
        return lines;
    }

    @Test
    void condenseKeepsTheFirstErrorInsteadOfOnlyTheTail() {
        var lines = paddedLines(120, 10);
        var complete = LogExcerpter.excerpt(lines, 30_000);
        assertThat(complete).doesNotContain("omitted");

        var condensed = LogExcerpter.condense(complete, 5_000);

        assertThat(condensed).hasSizeLessThanOrEqualTo(5_000).contains("the real cause").contains("noise 119 ").contains("omitted");
        assertThat(LogExcerpter.condense(complete, 30_000)).isSameAs(complete);
    }

    @Test
    void condenseMergesTheGapsOfTheExcerpt() {
        var lines = paddedLines(200, 10);
        var excerpt = LogExcerpter.excerpt(lines, 30_000);
        assertThat(excerpt).contains("[... 106 lines omitted ...]");

        var condensed = LogExcerpter.condense(excerpt, 3_000);

        assertThat(condensed).hasSizeLessThanOrEqualTo(3_000).contains("the real cause").contains("noise 199 ");
        var gapMarker = Pattern.compile("\\[\\.\\.\\. (\\d+) lines? omitted \\.\\.\\.]");
        var shown = 0;
        var omitted = 0;
        var previousWasGap = false;
        for (var line : condensed.split("\n")) {
            var gap = gapMarker.matcher(line);
            if (gap.matches()) {
                assertThat(previousWasGap).as("consecutive gap markers").isFalse();
                omitted += Integer.parseInt(gap.group(1));
                previousWasGap = true;
            } else {
                shown++;
                previousWasGap = false;
            }
        }
        assertThat(shown + omitted).isEqualTo(200);
    }

    private static List<String> paddedLines(int count, int errorAt) {
        var lines = new ArrayList<String>();
        for (var i = 0; i < count; i++) {
            lines.add(i == errorAt ? "[ERROR] the real cause" : "noise " + i + " " + "x".repeat(50));
        }
        return lines;
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
