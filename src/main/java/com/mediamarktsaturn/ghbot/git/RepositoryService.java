package com.mediamarktsaturn.ghbot.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.zip.ZipInputStream;

import org.kohsuke.github.GHRepository;

import com.mediamarktsaturn.ghbot.Command;
import com.mediamarktsaturn.ghbot.Result;
import com.mediamarktsaturn.ghbot.Result.Failure;
import com.mediamarktsaturn.ghbot.Result.Success;
import com.mediamarktsaturn.ghbot.events.PushEvent;
import com.mediamarktsaturn.ghbot.os.Util;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RepositoryService {

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
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        }
    }

    public CheckoutCommand createCheckoutCommand(PushEvent event) {
        return new CheckoutCommand(event.pushPayload().getRepository(), event.pushRef());
    }

    static Uni<Result<LocalRepository>> downloadReference(CheckoutCommand command, Command.Metadata metadata) {
        var ref = command.reference();
        return Uni.createFrom().item(() -> {
            metadata.writeToMDC();
            Path dir = null;
            try {
                dir = createTempDir(command.repositoryName());
                final var tmpDir = dir;
                command.repository().readZip(is -> {
                    extractToDirectory(new ZipInputStream(is), tmpDir);
                    return null;
                }, ref);
                Log.infof("Branch %s of %s downloaded to %s", metadata.gitRef(), metadata.repoFullName(), dir.toString());
                return new Success<>(new LocalRepository(dir));
            } catch (Exception e) {
                Log.errorf(e, "Failed to provide ref %s of %s", ref, metadata.repoFullName());
                removeTempDir(dir);
                return new Failure<>(e);
            }
        });
    }

    static void extractToDirectory(ZipInputStream zis, Path dir) throws IOException {
        try (zis) {
            var entry = Objects.requireNonNull(zis.getNextEntry());
            // Downloaded zip from GitHub always wraps the content in a single folder, we unwrap that.
            var wrappingFolderName = entry.getName();
            if (!entry.isDirectory() || wrappingFolderName.startsWith("/") || !wrappingFolderName.endsWith("/")) {
                throw new IllegalStateException("Zip does not start with a folder");
            }
            var wrappingDirLength = wrappingFolderName.length();
            while ((entry = zis.getNextEntry()) != null) {
                var zipFile = dir.resolve(entry.getName().substring(wrappingDirLength));
                if (entry.isDirectory()) {
                    Files.createDirectories(zipFile);
                } else {
                    Files.write(zipFile, zis.readAllBytes(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
                }
            }
        }
    }

    private static Path createTempDir(String repositoryName) throws IOException {
        return Files.createTempDirectory(repositoryName);
    }

    private static void removeTempDir(Path dir) {
        if (dir != null) {
            Util.removeAsync(dir);
        }
    }
}
