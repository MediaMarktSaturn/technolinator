package com.mediamarktsaturn.ghbot;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.subscription.UniSubscriber;
import io.smallrye.mutiny.subscription.UniSubscription;

public final class Commons {

    private Commons() {
        // utility stuff only
    }

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
