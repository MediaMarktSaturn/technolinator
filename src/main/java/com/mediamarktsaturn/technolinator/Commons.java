package com.mediamarktsaturn.technolinator;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

/**
 * Reusable constants and functions
 */
public final class Commons {

    private Commons() {
        // utility stuff only
    }

    /**
     * GitHub rejects comments and issue bodies longer than 65536 characters, stay well below.
     */
    public static final int MAX_COMMENT_LENGTH = 60_000;

    /**
     * Cuts the [content] to at most [maxLength] characters if it is longer, preferably at a line end, and appends the [note] then.
     */
    public static String truncate(String content, int maxLength, String note) {
        if (content.length() <= maxLength) {
            return content;
        }
        var end = Math.max(0, maxLength - note.length());
        var lineEnd = content.lastIndexOf('\n', end);
        return content.substring(0, lineEnd > 0 ? lineEnd : end) + note;
    }

    /**
     * Subscriber that does nothing but triggering the pipeline.
     */
    public static final UniSubscriber<? super Object> NOOP_SUBSCRIBER = new UniSubscriber<>() {

        @Override
        public void onSubscribe(UniSubscription subscription) {
            // nothing to do
        }

        @Override
        public void onItem(Object item) {
            // ignore item
        }

        @Override
        public void onFailure(Throwable failure) {
            Log.warn("Failure ignored", failure);
        }
    };
}
