package com.mediamarktsaturn.technolinator.report;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Condenses the output of a failed process to the parts which are worth reading.
 * <p>
 * Build tools are chatty and often print the root cause far away from the end of the output.
 * So an excerpt consists of the first lines around anything which looks like an error, and the tail of the output.
 */
public final class LogExcerpter {

    static final int TAIL_LINES = 80;
    static final int CONTEXT_LINES = 3;
    static final int MAX_LINE_LENGTH = 400;
    private static final int GAP_MARKER_COST = 40;

    private static final Pattern ANSI = Pattern.compile("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F]");
    private static final Pattern ERROR_LINE = Pattern.compile(
        "(?i)\\b(fatal|failed|failure|panic)\\b|(error|exception)\\b|could not|cannot |unable to|ERR!|\\[ERROR]");

    private LogExcerpter() {
        // utility
    }

    /**
     * Brings output lines into a printable form: no escape sequences or control characters,
     * progress bar rewrites resolved, over-long lines cut, consecutive duplicates and surrounding blank lines removed.
     */
    public static List<String> cleanLines(List<String> rawLines) {
        return cleanLines(rawLines, UnaryOperator.identity());
    }

    /**
     * Like {@link #cleanLines(List)}, but applies the given redaction to the printable lines before they are cut,
     * as a secret must not be left half visible by cutting a line in the middle of it.
     */
    public static List<String> cleanLines(List<String> rawLines, UnaryOperator<List<String>> redaction) {
        var cleaned = new ArrayList<String>(rawLines.size());
        String previous = null;
        int repeated = 0;
        for (var raw : redaction.apply(rawLines.stream().map(LogExcerpter::printable).toList())) {
            var line = truncate(raw);
            if (previous != null && !line.isBlank() && line.equals(previous)) {
                repeated++;
                continue;
            }
            flushRepeats(cleaned, repeated);
            repeated = 0;
            if (line.isBlank() && (cleaned.isEmpty() || cleaned.getLast().isEmpty())) {
                continue;
            }
            cleaned.add(line);
            previous = line;
        }
        flushRepeats(cleaned, repeated);
        while (!cleaned.isEmpty() && cleaned.getLast().isEmpty()) {
            cleaned.removeLast();
        }
        return cleaned;
    }

    private static void flushRepeats(List<String> cleaned, int repeated) {
        if (repeated > 0) {
            cleaned.add("[... previous line repeated %d more times]".formatted(repeated));
        }
    }

    private static String printable(String raw) {
        var line = raw == null ? "" : raw;
        if (line.indexOf('\r') >= 0) {
            // progress output rewrites the line, only what's left in the end is visible
            var segments = line.split("\r");
            line = "";
            for (var i = segments.length - 1; i >= 0; i--) {
                if (!segments[i].isBlank()) {
                    line = segments[i];
                    break;
                }
            }
        }
        line = ANSI.matcher(line).replaceAll("");
        line = CONTROL_CHARS.matcher(line).replaceAll("");
        return line.stripTrailing();
    }

    private static String truncate(String line) {
        return line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + " [...]" : line;
    }

    /**
     * @return the index of the first line looking like an error, or -1 if there is none
     */
    public static int firstErrorLineIndex(List<String> cleanedLines) {
        for (var i = 0; i < cleanedLines.size(); i++) {
            if (ERROR_LINE.matcher(cleanedLines.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return the first line looking like an error, or empty if there is none
     */
    public static String firstErrorLine(List<String> cleanedLines) {
        var idx = firstErrorLineIndex(cleanedLines);
        return idx < 0 ? "" : cleanedLines.get(idx).strip();
    }

    /**
     * @param lines    already cleaned lines, see {@link #cleanLines(List)}, and redacted
     * @param maxChars upper limit of the result length
     */
    public static String excerpt(List<String> lines, int maxChars) {
        var count = lines.size();
        if (count == 0) {
            return "";
        }

        var tailStart = Math.max(0, count - TAIL_LINES);
        while (tailStart < count - 1 && size(lines, tailStart, count) > maxChars) {
            tailStart++;
        }

        var budget = maxChars - size(lines, tailStart, count);
        var keep = new boolean[tailStart];
        for (var i = 0; i < tailStart; i++) {
            if (keep[i] || !ERROR_LINE.matcher(lines.get(i)).find()) {
                continue;
            }
            var from = Math.max(0, i - CONTEXT_LINES);
            var to = Math.min(tailStart - 1, i + CONTEXT_LINES);
            var cost = GAP_MARKER_COST;
            for (var j = from; j <= to; j++) {
                if (!keep[j]) {
                    cost += lines.get(j).length() + 1;
                }
            }
            if (cost > budget) {
                // the first errors are the most interesting ones, so don't skip ahead to later ones
                break;
            }
            budget -= cost;
            for (var j = from; j <= to; j++) {
                keep[j] = true;
            }
        }

        var result = new StringBuilder();
        var omitted = 0;
        for (var i = 0; i < count; i++) {
            if (i >= tailStart || keep[i]) {
                if (omitted > 0) {
                    result.append("[... ").append(omitted).append(omitted == 1 ? " line" : " lines").append(" omitted ...]\n");
                    omitted = 0;
                }
                result.append(lines.get(i)).append('\n');
            } else {
                omitted++;
            }
        }
        if (!result.isEmpty()) {
            result.setLength(result.length() - 1);
        }
        return fit(result.toString(), maxChars);
    }

    /**
     * Cuts the beginning of the text, if it exceeds the limit, as the end of a log is the more important part.
     */
    public static String fit(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        var marker = "[... truncated ...]\n";
        var start = text.length() - Math.max(0, maxChars - marker.length());
        var lineStart = text.indexOf('\n', start);
        if (lineStart >= 0 && lineStart < text.length() - 1) {
            start = lineStart + 1;
        }
        return marker + text.substring(start);
    }

    private static int size(List<String> lines, int from, int to) {
        var size = 0;
        for (var i = from; i < to; i++) {
            size += lines.get(i).length() + 1;
        }
        return size;
    }
}
