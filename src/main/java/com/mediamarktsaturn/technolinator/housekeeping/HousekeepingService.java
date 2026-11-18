package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Orchestrates a housekeeping run, which is a single walk through the repositories of GitHub and the projects of Dependency-Track:
 * <ol>
 *     <li>lists all repositories of all installations of the GitHub app (using the very same app credentials as the event handling,
 *     no webhook event is required), and all projects of Dependency-Track, in parallel</li>
 *     <li>pairs the repositories with their projects, see {@link UnitMatcher}</li>
 *     <li>hands every resulting {@link HousekeepingUnit} to the {@link HousekeepingWorker} exactly once, up to {@code worker_concurrency} in parallel</li>
 * </ol>
 * A run can be restricted to some repositories and projects, see {@link HousekeepingSelection}. Then, only these are looked up instead of
 * listing everything, see {@link TargetedListing}, and the metrics telling when the last complete run happened are left alone.
 * <p>
 * Only one run is active at a time, there's no scheduling logic in here, see {@link HousekeepingScheduler} and {@link HousekeepingEndpoint}.
 */
@ApplicationScoped
public class HousekeepingService {

    private final GitHubRepositoryIndexer indexer;
    private final DependencyTrackClient dtrackClient;
    private final HousekeepingWorker worker;
    private final TargetedListing targetedListing;
    private final MeterRegistry meterRegistry;
    private final boolean dryRun;
    private final Duration runTimeout;
    private final int workerConcurrency;
    private final Set<String> enabledRepos;

    private final AtomicBoolean running = new AtomicBoolean(false);
    // micrometer holds gauge state weakly, so it needs to be referenced from here
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);
    private final AtomicLong lastCompletedEpochSeconds = new AtomicLong(0);

    @Inject
    public HousekeepingService(
        GitHubRepositoryIndexer indexer,
        DependencyTrackClient dtrackClient,
        HousekeepingWorker worker,
        MeterRegistry meterRegistry,
        @ConfigProperty(name = "app.enabled_repos") List<String> enabledRepos,
        @ConfigProperty(name = "app.housekeeping.dry_run") boolean dryRun,
        @ConfigProperty(name = "app.housekeeping.timeout") Duration runTimeout,
        @ConfigProperty(name = "app.housekeeping.worker_concurrency") int workerConcurrency
    ) {
        this.indexer = indexer;
        this.dtrackClient = dtrackClient;
        this.worker = worker;
        this.targetedListing = new TargetedListing(indexer, dtrackClient);
        this.meterRegistry = meterRegistry;
        this.enabledRepos = enabledRepos.stream().map(String::trim).filter(name -> !name.isEmpty()).map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.dryRun = dryRun;
        this.runTimeout = runTimeout;
        this.workerConcurrency = Math.max(1, workerConcurrency);
        meterRegistry.gauge("housekeeping_last_success_epoch_seconds", lastSuccessEpochSeconds);
        meterRegistry.gauge("housekeeping_last_completed_epoch_seconds", lastCompletedEpochSeconds);
        meterRegistry.counter("housekeeping_installation_failures");
    }

    /**
     * Performs a housekeeping run for everything. See {@link #run(HousekeepingSelection)}.
     */
    public Uni<Void> runAll() {
        return run(HousekeepingSelection.all());
    }

    /**
     * @return whether a run is in progress at the moment
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Performs a housekeeping run for the [selection]. The returned {@link Uni} never fails, problems are logged and reported via metrics.
     * Does nothing, if another run is still in progress or nothing is enabled. Nothing happens before the returned {@link Uni} is subscribed to.
     */
    public Uni<Void> run(HousekeepingSelection selection) {
        return Uni.createFrom().deferred(() -> claim() ? runClaimed(selection) : Uni.createFrom().voidItem());
    }

    /**
     * Starts a housekeeping run for the [selection] in the background, unless another run is in progress.
     * Claiming the run happens right away, so callers can tell reliably whether their run got started.
     *
     * @return {@code false}, if another run is still in progress
     */
    public boolean startInBackground(HousekeepingSelection selection) {
        if (!claim()) {
            return false;
        }
        runClaimed(selection).subscribe().with(
            ignored -> {
            },
            failure -> Log.error("Housekeeping run failed", failure)
        );
        return true;
    }

    private boolean claim() {
        if (running.compareAndSet(false, true)) {
            return true;
        }
        Log.warn("Housekeeping run skipped, the previous one is still in progress");
        return false;
    }

    /**
     * Requires the run to be {@link #claim() claimed}, releases it in any case.
     */
    private Uni<Void> runClaimed(HousekeepingSelection selection) {
        try {
            var enabledActions = worker.enabledActions();
            if (enabledActions.isEmpty()) {
                running.set(false);
                Log.info("Housekeeping run skipped, no action enabled");
                return Uni.createFrom().voidItem();
            }
            return execute(selection, enabledActions);
        } catch (RuntimeException e) {
            running.set(false);
            Log.error("Housekeeping run aborted, could not be started", e);
            return Uni.createFrom().voidItem();
        }
    }

    private Uni<Void> execute(HousekeepingSelection selection, List<String> enabledActions) {
        var run = new HousekeepingRun(UUID.randomUUID().toString().substring(0, 8), Instant.now(), dryRun);
        var sample = Timer.start(meterRegistry);
        run.writeToMDC();
        Log.infof("Housekeeping run %s started%s with actions %s%s", run.id(), run.dryRun() ? " in dry-run mode" : "", enabledActions,
            selection.isAll() ? "" : ", restricted to repositories " + new TreeSet<>(selection.repositories()) + " and projects " + new TreeSet<>(selection.projects()));
        worker.begin(run);

        return crawl(run, selection)
            .invoke(summary -> reportCompletion(run, summary, selection))
            .onTermination().invoke(() -> {
                try {
                    worker.end(run);
                    sample.stop(meterRegistry.timer("housekeeping_run_duration"));
                } finally {
                    running.set(false);
                }
            })
            // safety net only, all steps above handle their failures
            .onFailure().invoke(failure -> Log.errorf(failure, "Housekeeping run %s failed", run.id()))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    private Uni<HousekeepingSummary> crawl(HousekeepingRun run, HousekeepingSelection selection) {
        var progress = new AtomicReference<Supplier<HousekeepingSummary>>(HousekeepingSummary::notStarted);
        // deferred, so even a listing failing to get started releases the run via the handlers below
        var listing = Uni.createFrom().deferred(() -> selection.isAll()
            ? Uni.combine().all().unis(indexer.build(run), dtrackClient.listProjects(false)).asTuple()
                .map(listings -> new TargetedListing.Listing(listings.getItem1(), listings.getItem2()))
            : targetedListing.load(run, selection));
        return listing
            .chain(listings -> selection.isAll() ? confirmGone(run, listings) : Uni.createFrom().item(listings))
            .chain(listings -> {
                var index = listings.index();
                var projects = listings.projects();
                run.writeToMDC();
                var allUnits = UnitMatcher.match(run, index, projects, enabledRepos);
                // The absence of a repository or of projects only tells something if everything was listed. Dependency-Track fails the run
                // if its projects can't be listed completely, but installations failing to list their repositories are only left out.
                // So with such, solely units having a repository and projects are trustworthy, the others might just be missing their counterpart.
                var reliable = index.failedInstallations() == 0;
                var trusted = reliable ? allUnits : allUnits.stream().filter(unit -> unit.repository().isPresent() && !unit.projects().isEmpty()).toList();
                if (!reliable) {
                    Log.warnf("The repositories of GitHub could not be listed completely (%d installations failed), so only the %d of %d units having a repository and projects are processed",
                        index.failedInstallations(), trusted.size(), allUnits.size());
                }
                var units = trusted.stream().filter(selection::selects).toList();
                if (!selection.isAll()) {
                    var unmatched = selection.unmatched(allUnits);
                    if (!unmatched.isEmpty()) {
                        Log.warnf("Nothing found for the selected %s", unmatched);
                    }
                }
                var failedUnits = new AtomicInteger();
                progress.set(() -> summarize(index, projects, units, failedUnits.get(), true));
                Log.infof("Housekeeping found %d repositories of %d GitHub accounts and %d projects in Dependency-Track, resulting in %d units to take care of",
                    index.repositories().size(), index.owners(), projects.size(), units.size());

                return Multi.createFrom().iterable(units)
                    .onItem().transformToUni(unit -> Uni.createFrom().deferred(() -> worker.process(unit))
                        // the worker handles its failures itself, this is just a safety net
                        .onFailure().invoke(failure -> Log.errorf(failure, "Housekeeping failed for %s", unit.subject()))
                        .onFailure().recoverWithItem(false)
                        .invoke(success -> {
                            if (!success) {
                                failedUnits.incrementAndGet();
                            }
                        }))
                    .merge(workerConcurrency)
                    .onItem().ignoreAsUni()
                    .map(ignored -> summarize(index, projects, units, failedUnits.get(), false));
            })
            // visits already in progress aren't interrupted, but no further one is started
            .ifNoItem().after(runTimeout).recoverWithItem(() -> {
                run.writeToMDC();
                Log.errorf("Housekeeping run %s timed out after %s", run.id(), runTimeout);
                return aborted(progress.get().get());
            })
            .onFailure().recoverWithItem(failure -> {
                run.writeToMDC();
                Log.errorf(failure, "Housekeeping run %s failed, could not list the repositories of GitHub or the projects of Dependency-Track", run.id());
                return aborted(progress.get().get());
            });
    }

    /**
     * Repositories not listed by the installation of their account aren't necessarily gone: GitHub keeps renamed and transferred repositories alive
     * (and redirects to them), while the projects of Dependency-Track still reference the old location. Archiving the projects of those,
     * just because the old name is missing, would hit repositories that are neither archived nor deleted.
     * So, the repositories referenced by projects that are missing in the listings are looked up on their own, and only the ones
     * GitHub doesn't know at all stay {@link GitHubRepositoryIndex.State#GONE}, the others become {@link GitHubRepositoryIndex.State#UNKNOWN}.
     * Also, a repository of the same name existing under another account may be the transferred one, even if GitHub can't tell.
     * <p>
     * Only the repositories that might get projects archived are looked up: the ones referenced by active projects or their ancestors
     * (projects without references follow them, see {@link UnitMatcher}). Repositories deleted long ago, having archived projects only,
     * would cost a request to GitHub in every run otherwise, without any effect. They're {@link GitHubRepositoryIndex.State#UNKNOWN}, as unconfirmed.
     */
    private Uni<TargetedListing.Listing> confirmGone(HousekeepingRun run, TargetedListing.Listing listing) {
        var index = listing.index();
        var missing = missingRepositories(index, listing.projects());
        if (missing.isEmpty()) {
            return Uni.createFrom().item(listing);
        }
        var candidates = missingRepositories(index, activeProjectsAndAncestors(listing.projects()));

        var ownersByName = new HashMap<String, Set<String>>();
        index.repositories().forEach(repository ->
            ownersByName.computeIfAbsent(repository.reference().name(), name -> new HashSet<>()).add(repository.reference().ownerKey()));
        var movedElsewhere = candidates.stream()
            .filter(reference -> ownersByName.getOrDefault(reference.name(), Set.of()).stream().anyMatch(ownerKey -> !ownerKey.equals(reference.ownerKey())))
            .collect(Collectors.toSet());
        var toVerify = candidates.stream().filter(reference -> !movedElsewhere.contains(reference)).toList();

        var confirmation = toVerify.isEmpty()
            ? Uni.createFrom().item(Set.<RepositoryReference>of())
            : indexer.confirmGone(run, toVerify)
            .onFailure().invoke(failure -> {
                run.writeToMDC();
                Log.errorf(failure, "Could not look up the %d repositories missing in the listings, treating them as unknown", toVerify.size());
            })
            .onFailure().recoverWithItem(Set.of());
        return confirmation
            .map(confirmed -> {
                var unconfirmed = missing.stream().filter(reference -> !confirmed.contains(reference)).toList();
                run.writeToMDC();
                Log.infof("Of %d repositories referenced by projects, but missing in the listings of GitHub, %d are confirmed gone, %d exist under another name or account or couldn't be looked up (%d of them have a namesake elsewhere), %d have archived projects only and weren't looked up",
                    missing.size(), confirmed.size(), candidates.size() - confirmed.size(), movedElsewhere.size(), missing.size() - candidates.size());
                return new TargetedListing.Listing(index.withUnconfirmedGone(unconfirmed), listing.projects());
            });
    }

    private static List<RepositoryReference> missingRepositories(GitHubRepositoryIndex index, Collection<DependencyTrackProject> projects) {
        return projects.stream()
            .flatMap(project -> UnitMatcher.referencesOf(project).stream())
            .distinct()
            .filter(reference -> index.lookup(reference) == GitHubRepositoryIndex.State.GONE)
            .toList();
    }

    private static Set<DependencyTrackProject> activeProjectsAndAncestors(List<DependencyTrackProject> projects) {
        var byUuid = new HashMap<String, DependencyTrackProject>();
        projects.forEach(project -> byUuid.putIfAbsent(project.uuid(), project));
        var relevant = new HashSet<DependencyTrackProject>();
        projects.stream().filter(DependencyTrackProject::active).forEach(project -> {
            // stops at known ones, which guards against cyclic hierarchies, too
            var current = project;
            while (current != null && relevant.add(current)) {
                current = current.parentUuid().map(byUuid::get).orElse(null);
            }
        });
        return relevant;
    }

    private static HousekeepingSummary aborted(HousekeepingSummary summary) {
        return new HousekeepingSummary(summary.installations(), summary.failedInstallations(), summary.repositories(), summary.projects(),
            summary.unitsWithBoth(), summary.unitsWithoutProjects(), summary.unitsWithoutRepository(), summary.failedUnits(), true);
    }

    private static HousekeepingSummary summarize(
        GitHubRepositoryIndex index,
        List<DependencyTrackProject> projects,
        List<HousekeepingUnit> units,
        int failedUnits,
        boolean aborted
    ) {
        return new HousekeepingSummary(
            index.owners() + index.failedInstallations(),
            index.failedInstallations(),
            index.repositories().size(),
            projects.size(),
            (int) units.stream().filter(unit -> unit.repository().isPresent() && !unit.projects().isEmpty()).count(),
            (int) units.stream().filter(unit -> unit.repository().isPresent() && unit.projects().isEmpty()).count(),
            (int) units.stream().filter(unit -> unit.repository().isEmpty()).count(),
            failedUnits,
            aborted
        );
    }

    private void reportCompletion(HousekeepingRun run, HousekeepingSummary summary, HousekeepingSelection selection) {
        var now = Instant.now().getEpochSecond();
        // a partial run doesn't tell anything about the state of the whole
        if (selection.isAll()) {
            if (!summary.aborted()) {
                lastCompletedEpochSeconds.set(now);
            }
            if (summary.complete()) {
                lastSuccessEpochSeconds.set(now);
            }
        }
        if (summary.failedInstallations() > 0) {
            meterRegistry.counter("housekeeping_installation_failures").increment(summary.failedInstallations());
        }
        run.writeToMDC();
        Log.infof("Housekeeping run %s %s after %s: %s",
            run.id(), summary.aborted() ? "was aborted" : summary.complete() ? "completed" : "completed incompletely", Duration.between(run.startedAt(), Instant.now()), summary);
    }
}
