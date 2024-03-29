package com.mediamarktsaturn.technolinator;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import org.testcontainers.images.ParsedDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
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

    static DockerImageName fromDockerfile(String dockerfile) {
        var resolvedDockerfile = new ParsedDockerfile(Path.of(MountableFile.forClasspathResource("testcontainers/" + dockerfile).getResolvedPath()));
        return DockerImageName.parse(resolvedDockerfile.getDependencyImageNames().iterator().next());
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
