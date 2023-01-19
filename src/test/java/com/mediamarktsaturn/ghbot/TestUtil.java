package com.mediamarktsaturn.ghbot;

import java.nio.file.Path;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.testcontainers.images.ParsedDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public interface TestUtil {

    static <T> T await(Future<T> future) {
        try {
            return future.get(15, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static DockerImageName fromDockerfile(String dockerfile) {
        var resolvedDockerfile = new ParsedDockerfile(Path.of(MountableFile.forClasspathResource("testcontainers/" + dockerfile).getResolvedPath()));
        return DockerImageName.parse(resolvedDockerfile.getDependencyImageNames().iterator().next());
    }

    static <T> Consumer<T> ignore() {
        return any -> {};
    }
}
