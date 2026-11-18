package com.mediamarktsaturn.technolinator.os;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Removes the environment audit tables, cdxgen prints on every run, from line based process output:
 * <pre>
 * +------------------------+------------+--------------------------+-------------+
 * |                       SECURE MODE: Environment audit                         |
 * |                                    5 low                                     |
 * +------------------------+------------+--------------------------+-------------+
 * | Category               | Severity   | Variable(s)              | Details     |
 * +------------------------+------------+--------------------------+-------------+
 * | Credential Exposure    | LOW        | GITHUB_TOKEN             | Credential- |
 * |                        |            | GITHUB_USER              | like envir  |
 * +------------------------+------------+--------------------------+-------------+
 * </pre>
 * Besides this "SECURE MODE: Environment audit" table, cdxgen can print further tables of the same report right after it:
 * "SBOM Generation Environment Assessment", "Findings", "Effective Configuration" and "Recommendations for TLP:...".
 * In debug mode it additionally prints a "cdxgen debug activity summary" table at the end of a run, with one multi-line entry
 * per file read, command executed or environment variable read, which is just as useless here.
 * <p>
 * A table is recognised structurally rather than by its content: it starts with a border line directly followed
 * by a row carrying one of these titles, and spans all subsequent border and row lines. A border line directly
 * following a border line ends the table, as it is the top of the next one, which is dropped as well if it's
 * a report table, but otherwise passed through. Every other table and any other output is passed through unchanged,
 * especially the abort message of the secure mode, which explains why cdxgen stopped.
 * <p>
 * The filter is stateful and must be used for a single process only, call {@link #flush(Consumer)} when the output ended.
 */
final class SecureModeAuditFilter {

    private static final char ESC = '\u001B';
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    /**
     * Horizontal table line, e.g. +-----+-----+ including the unicode box drawing variants (light, heavy, double, rounded)
     */
    private static final Pattern BORDER = Pattern.compile("\\s*[+┌┏╔├┣╠└┗╚╭╰][-─━═+┬┳╦┼╋╬┴┻╩]+[+┐┓╗┤┫╣┘┛╝╮╯]\\s*");

    /**
     * Table content line, e.g. | foo | bar |
     */
    private static final Pattern ROW = Pattern.compile("\\s*[|│┃║].*[|│┃║]\\s*");

    /**
     * Title row of an audit report table, e.g. |    SECURE MODE: Environment audit    |
     */
    private static final Pattern TITLE_ROW = Pattern.compile(
        "\\s*[|│┃║]\\s*(?:SECURE\\s+MODE\\b|SBOM\\s+Generation\\s+Environment\\s+Assessment\\b"
            + "|cdxgen\\s+(?:debug|dry-run)\\s+activity\\s+summary\\b"
            + "|[^\\w|│┃║]*(?:Findings\\b|Effective\\s+Configuration\\b|Recommendations\\s+for\\s+TLP\\b))"
            + ".*[|│┃║]\\s*",
        Pattern.CASE_INSENSITIVE);

    /**
     * A border line held back until the next line tells whether it opens the audit table
     */
    private String pendingBorder;
    private boolean inAuditTable;
    private boolean lastWasBorder;

    /**
     * Processes a line of output, passing it on to the sink unless it's part of the audit table
     */
    void accept(String line, Consumer<String> sink) {
        var plain = stripAnsi(line);
        if (inAuditTable) {
            var border = BORDER.matcher(plain).matches();
            if (border && lastWasBorder) {
                // top of a following table
                inAuditTable = false;
            } else if (border || ROW.matcher(plain).matches()) {
                lastWasBorder = border;
                return;
            } else {
                inAuditTable = false;
            }
        } else if (pendingBorder != null) {
            if (TITLE_ROW.matcher(plain).matches()) {
                pendingBorder = null;
                inAuditTable = true;
                lastWasBorder = false;
                return;
            }
            flush(sink);
        }

        if (BORDER.matcher(plain).matches()) {
            pendingBorder = line;
        } else {
            sink.accept(line);
        }
    }

    /**
     * Passes on a held back line, to be called when the output ended
     */
    void flush(Consumer<String> sink) {
        if (pendingBorder != null) {
            sink.accept(pendingBorder);
            pendingBorder = null;
        }
    }

    private static String stripAnsi(String line) {
        return line.indexOf(ESC) < 0 ? line : ANSI_ESCAPE.matcher(line).replaceAll("");
    }
}
