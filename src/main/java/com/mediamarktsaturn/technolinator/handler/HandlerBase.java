package com.mediamarktsaturn.technolinator.handler;

import com.mediamarktsaturn.technolinator.Command;
import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.events.Event;
import com.mediamarktsaturn.technolinator.git.LocalRepository;
import com.mediamarktsaturn.technolinator.git.RepositoryService;
import com.mediamarktsaturn.technolinator.sbom.CdxgenClient;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Deprecated
public abstract class HandlerBase {

    protected static final String DTRACK_PROJECT_SEARCH_TMPL = "%s/projects?searchText=%s";

    protected final RepositoryService repoService;
    protected final CdxgenClient cdxgenClient;
    protected final String dtrackUrl;
    private final boolean fetchLicenses;


    protected HandlerBase(RepositoryService repoService, CdxgenClient cdxgenClient, boolean fetchLicenses, String dtrackUrl) {
        this.repoService = repoService;
        this.cdxgenClient = cdxgenClient;
        this.fetchLicenses = fetchLicenses;
        this.dtrackUrl = dtrackUrl;
    }

    protected Uni<Tuple2<List<Result<CdxgenClient.SBOMGenerationResult>>, LocalRepository>> checkoutAndGenerateSBOMs(Event<?> event, Command.Metadata metadata) {
        var checkout = Objects.requireNonNull(repoService).createCheckoutCommand(event.repository(), event.ref());
        return checkout.execute(metadata)
            .chain(checkoutResult -> generateSboms(event, checkoutResult, metadata));
    }

    Uni<Tuple2<List<Result<CdxgenClient.SBOMGenerationResult>>, LocalRepository>> generateSboms(Event<?> event, Result<LocalRepository> checkoutResult, Command.Metadata metadata) {
        metadata.writeToMDC();
        return switch (checkoutResult) {
            case Result.Success<LocalRepository> s -> {
                var localRepo = s.result();
                var cmds = Objects.requireNonNull(cdxgenClient).createCommands(localRepo.dir(), event.getRepoName(), fetchLicenses, event.config());
                yield executeCommands(cmds, metadata).map(result -> Tuple2.of(result, localRepo));
            }
            case Result.Failure<LocalRepository> f -> {
                Log.errorf(f.cause(), "Aborting analysis of repo %s, branch %s because of checkout failure", event.repoUrl(), event.branch());
                yield Uni.createFrom().item(Tuple2.of(List.of(Result.failure(f.cause())), null));
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static Uni<List<Result<CdxgenClient.SBOMGenerationResult>>> executeCommands(List<CdxgenClient.SbomCreationCommand> commands, Command.Metadata metadata) {
        return Uni.combine().all().unis(
            commands.stream().map(cmd -> cmd.execute(metadata)).toList()
        ).combinedWith(results -> {
            metadata.writeToMDC();
            var resultClasses = results.stream().collect(Collectors.groupingBy(Object::getClass))
                .entrySet().stream().map(e -> "%s x %s".formatted(e.getValue().size(), e.getKey().getSimpleName()))
                .collect(Collectors.joining(", "));
            var sbomClasses = results.stream()
                .filter(r -> r instanceof Result.Success).map(r -> ((Result.Success<CdxgenClient.SBOMGenerationResult>) r).result())
                .collect(Collectors.groupingBy(Object::getClass))
                .entrySet().stream().map(e -> "%s x %s".formatted(e.getValue().size(), e.getKey().getSimpleName()))
                .collect(Collectors.joining(", "));
            Log.infof("Analysis of repo %s, branch %s for %s projects resulted in %s with SBOMs %s",
                metadata.repoFullName(), metadata.gitRef(), commands.size(), resultClasses, sbomClasses);
            return (List<Result<CdxgenClient.SBOMGenerationResult>>) results;
        });
    }

    protected String buildDTrackProjectSearchUrl(String projectName) {
        return DTRACK_PROJECT_SEARCH_TMPL.formatted(dtrackUrl, projectName);
    }
}
