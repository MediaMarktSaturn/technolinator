package com.mediamarktsaturn.technolinator.handler;

import static com.mediamarktsaturn.technolinator.git.RepositoryService.buildProjectNameFromEvent;

import java.nio.file.Path;
import java.util.Optional;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.Event;
import com.mediamarktsaturn.technolinator.git.LocalRepository;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;

public abstract class HandlerBase {

    protected final RepositoryService repoService;
    protected final CdxgenClient cdxgenClient;
    private final boolean fetchLicenses;

    // dummy constructor required by the ARC
    protected HandlerBase() {
        this.repoService = null;
        this.cdxgenClient = null;
        this.fetchLicenses = false;
    }

    protected HandlerBase(RepositoryService repoService, CdxgenClient cdxgenClient, boolean fetchLicenses) {
        this.repoService = repoService;
        this.cdxgenClient = cdxgenClient;
        this.fetchLicenses = fetchLicenses;
    }

    protected Uni<Tuple2<Result<CdxgenClient.SBOMGenerationResult>, LocalRepository>> checkoutAndGenerateSBOM(Event<?> event, Command.Metadata metadata) {
        var checkout = repoService.createCheckoutCommand(event.repository(), event.ref());
        return checkout.execute(metadata)
            .chain(checkoutResult -> generateSbom(event, checkoutResult, metadata));
    }

    Uni<Tuple2<Result<CdxgenClient.SBOMGenerationResult>, LocalRepository>> generateSbom(Event<?> event, Result<LocalRepository> checkoutResult, Command.Metadata metadata) {
        metadata.writeToMDC();
        return switch (checkoutResult) {
            case Result.Success<LocalRepository> s -> {
                var localRepo = s.result();
                var cmd = cdxgenClient.createCommand(buildAnalysisDirectory(localRepo, event.config()), buildProjectNameFromEvent(event), fetchLicenses, event.config());
                yield cmd.execute(metadata).map(result -> Tuple2.of(result, localRepo));
            }
            case Result.Failure<LocalRepository> f -> {
                Log.errorf(f.cause(), "Aborting analysis of repo %s, branch %s because of checkout failure", event.repoUrl(), event.branch());
                yield Uni.createFrom().item(Tuple2.of(Result.failure(f.cause()), null));
            }
        };
    }

    static Path buildAnalysisDirectory(LocalRepository repo, Optional<TechnolinatorConfig> config) {
        return config
            .map(TechnolinatorConfig::analysis)
            .map(TechnolinatorConfig.AnalysisConfig::location)
            .map(String::trim)
            .map(s -> s.startsWith("/") ? s.substring(1) : s)
            .map(repo.dir()::resolve)
            .orElse(repo.dir());
    }
}
