package com.mediamarktsaturn.technolinator.report;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hidden HTML comment in the first line of status issues and pull-request comments.
 * It identifies them as written by Technolinator and carries the little state needed to maintain them.
 * <pre>
 * &lt;!-- technolinator:status v=1 state=failed started=2026-01-01T10:00:00Z sha=abc123 err=1a2b3c4d --&gt;
 * </pre>
 *
 * @param state   what the text it is attached to tells
 * @param started start of the analysis which wrote it, to ignore results of older analyses
 * @param sha     the analysed commit, may be empty
 * @param err     {@link FailureReport#errorHash()}, may be empty
 */
public record StatusMarker(State state, Instant started, String sha, String err) {

    public enum State {
        /**
         * Analysis fails.
         */
        FAILED,
        /**
         * Status issue: analysis succeeded again.
         */
        RESOLVED,
        /**
         * Pull-request comment: analysis succeeded again after having failed.
         */
        RECOVERED
    }

    public static final String PREFIX = "<!-- technolinator:status";

    private static final Pattern MARKER = Pattern.compile("<!-- technolinator:status v=1 ([^>]*?)\\s*-->");

    public String render() {
        return "%s v=1 state=%s started=%s sha=%s err=%s -->".formatted(
            PREFIX,
            state.name().toLowerCase(Locale.ROOT),
            started.truncatedTo(ChronoUnit.MILLIS),
            sha == null || sha.isBlank() ? "-" : sha,
            err == null || err.isBlank() ? "-" : err
        );
    }

    /**
     * @return the marker found in the given text, or empty if there is none or it is malformed
     */
    public static Optional<StatusMarker> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        var matcher = MARKER.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        var values = new HashMap<String, String>();
        for (var pair : matcher.group(1).split("\\s+")) {
            var idx = pair.indexOf('=');
            if (idx > 0) {
                values.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        try {
            var state = State.valueOf(values.getOrDefault("state", "").toUpperCase(Locale.ROOT));
            var started = Instant.parse(values.getOrDefault("started", ""));
            return Optional.of(new StatusMarker(state, started, values.getOrDefault("sha", "-"), values.getOrDefault("err", "-")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * @return whether the given text carries a marker of Technolinator, even if it is not readable
     */
    public static boolean isPresentIn(String text) {
        return text != null && text.contains(PREFIX);
    }

    /**
     * @return the given text with the first marker replaced
     */
    public static String replace(String text, StatusMarker replacement) {
        return MARKER.matcher(text).replaceFirst(m -> Matcher.quoteReplacement(replacement.render()));
    }
}
