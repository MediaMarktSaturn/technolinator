package com.mediamarktsaturn.ghbot.git;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;

import jakarta.enterprise.context.ApplicationScoped;

import org.kohsuke.github.GHRepository;

import com.mediamarktsaturn.ghbot.Command;
import com.mediamarktsaturn.ghbot.Result;
import com.mediamarktsaturn.ghbot.Result.Failure;
import com.mediamarktsaturn.ghbot.Result.Success;
import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.os.ProcessHandler;
import com.mediamarktsaturn.ghbot.os.Util;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@ApplicationScoped
public class RepositoryService {

    private static final String DOWNLOAD = "download.zip";
    private static final String UNZIP_CMD = "unzip -q " + DOWNLOAD;

    public record CheckoutCommand(
        GHRepository repository,
        String reference
    ) implements Command<LocalRepository> {

        public String repositoryName() {
            return repository.getName();
        }

        @Override
        public Uni<Result<LocalRepository>> execute(Metadata metadata) {
            return downloadReference(this, metadata)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .chain(result ->
                    switch (result) {
                        case Success<LocalRepository> s -> unzip(s.result(), metadata);
                        case Failure<LocalRepository> f -> Uni.createFrom().item(f);
                    }
                );
        }
    }

    public CheckoutCommand createCheckoutCommand(PushEvent event) {
        return new CheckoutCommand(event.pushPayload().getRepository(), event.pushRef());
    }

    static Uni<Result<LocalRepository>> downloadReference(CheckoutCommand command, Command.Metadata metadata) {
        var ref = command.reference();
        return Uni.createFrom().item(() -> {
            metadata.writeToMDC();
            File dir = null;
            try {
                dir = createTempDir(command.repositoryName());
                final var tmpDir = dir;
                command.repository().readZip(zis -> {
                    try (FileOutputStream fos = new FileOutputStream(new File(tmpDir, DOWNLOAD)); zis) {
                        byte[] buffer = new byte[1048576];
                        int read;
                        while ((read = zis.read(buffer, 0, 1048576)) != -1) {
                            // TODO: check for elegant jdk methods
                            // TODO extract directly while downloading
                            fos.write(buffer, 0, read);
                        }
                        fos.flush();
                    }
                    return null;
                }, ref);
                Log.infof("Branch %s of %s downloaded to %s", metadata.gitRef(), metadata.repoFullName(), dir.getAbsolutePath());
                return new Success<>(new LocalRepository(dir));
            } catch (Exception e) {
                Log.errorf(e, "Failed to provide ref %s of %s", ref, metadata.repoFullName());
                removeTempDir(dir);
                return new Failure<>(e);
            }
        });
    }

    static Uni<Result<LocalRepository>> unzip(LocalRepository localRepo, Command.Metadata metadata) {
        metadata.writeToMDC();
        var dir = localRepo.dir();
        Uni<Result<LocalRepository>> result = ProcessHandler.run(UNZIP_CMD, dir, Map.of())
            .chain(processResult -> {
                    metadata.writeToMDC();
                    return switch (processResult) {
                        case ProcessHandler.ProcessResult.Success us ->
                            ProcessHandler.run("rm -f " + DOWNLOAD, dir, Map.of())
                                .map(deleteResult ->
                                    switch (deleteResult) {
                                        case ProcessHandler.ProcessResult.Success ds ->
                                            new Success<>(new LocalRepository(Objects.requireNonNull(dir.listFiles())[0]));
                                        case ProcessHandler.ProcessResult.Failure df -> new Failure<>(df.cause());
                                    }
                                );
                        case ProcessHandler.ProcessResult.Failure uf -> Uni.createFrom().item(new Failure<>(uf.cause()));
                    };
                }
            );
        return result.onTermination().invoke((unzipResult, unzipFailure, wasCancelled) -> {
            if (unzipResult instanceof Failure<LocalRepository> || unzipFailure != null) {
                localRepo.close();
            }
        });
    }

    private static File createTempDir(String repositoryName) throws Exception {
        return Files.createTempDirectory(repositoryName).toFile();
    }

    private static void removeTempDir(File dir) {
        if (dir != null) {
            Util.removeAsync(dir);
        }
    }
}
