package com.mediamarktsaturn.technolinator;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public interface TestUtil {

    static <T> T await(Uni<T> uni) {
        UniAssertSubscriber<T> uas = new UniAssertSubscriber<>();
        uni
            .onFailure().invoke(failure -> {
                Logger.getAnonymousLogger().log(Level.SEVERE, "Await failed", failure);
            })
            .subscribe().withSubscriber(uas);
        uas.awaitItem(Duration.ofMinutes(15));
        return uas.getItem();
    }

    static <T> Consumer<T> ignore() {
        return any -> {
        };
    }

    static URL url(String url) {
        try {
            return URI.create(url).toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
