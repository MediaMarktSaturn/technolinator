package com.mediamarktsaturn.ghbot;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

import org.testcontainers.images.ParsedDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import io.smallrye.mutiny.Uni;

public interface TestUtil {

    static <T> T await(Uni<T> uni) {
        try {
            return uni.await().atMost(Duration.ofMinutes(15));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static DockerImageName fromDockerfile(String dockerfile) {
        var resolvedDockerfile = new ParsedDockerfile(Path.of(MountableFile.forClasspathResource("testcontainers/" + dockerfile).getResolvedPath()));
        return DockerImageName.parse(resolvedDockerfile.getDependencyImageNames().iterator().next());
    }

    static <T> Consumer<T> ignore() {
        return any -> {
        };
    }
}
