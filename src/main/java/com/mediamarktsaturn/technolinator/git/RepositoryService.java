package com.mediamarktsaturn.technolinator.git;

import static com.mediamarktsaturn.technolinator.Commons.NOOP_SUBSCRIBER;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipInputStream;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kohsuke.github.GHRepository;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.Result.Failure;
import com.mediamarktsaturn.technolinator.Result.Success;
import com.mediamarktsaturn.technolinator.os.Util;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.unchecked.Unchecked;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Handles the local provisioning of GitHub repositories
 */
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

    private final MeterRegistry meterRegistry;
    private final boolean publishRepoMetrics;

    public RepositoryService(
        MeterRegistry meterRegistry,
        @ConfigProperty(name = "app.publish_repo_metrics")
        boolean publishRepoMetrics
    ) {
        this.meterRegistry = meterRegistry;
        this.publishRepoMetrics = publishRepoMetrics;
    }

    public CheckoutCommand createCheckoutCommand(GHRepository repository, String ref) {
        return new CheckoutCommand(repository, ref);
    }

    /**
     * Provides a remote repository referred to by [command] to the local filesystem
     */
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

    public void publishRepositoryMetrics(GHRepository repo) {
        if (publishRepoMetrics) {
            Uni.createFrom().item(Unchecked.supplier(() -> {
                    repo.listLanguages().forEach((lang, bytes) ->
                        meterRegistry.gauge("repo_language_bytes",
                            List.of(Tag.of("repo", repo.getName()), Tag.of("lang", lang)),
                            bytes)
                    );
                    return null;
                })).runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(failure -> Log.warnf(failure, "Could not publish repo metrics of %s", repo.getHtmlUrl()))
                .onFailure().recoverWithNull()
                .subscribe().withSubscriber(NOOP_SUBSCRIBER);
        }
    }
}
