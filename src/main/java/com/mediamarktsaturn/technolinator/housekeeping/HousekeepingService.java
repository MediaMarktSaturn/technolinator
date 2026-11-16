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
 * A run can be restricted to some repositories and projects, see {@link HousekeepingSelection}. Such a partial run doesn't touch the metrics
 * telling when the last complete run happened.
 * <p>
 * Only one run is active at a time, there's no scheduling logic in here, see {@link HousekeepingScheduler} and {@link HousekeepingEndpoint}.
 */
@ApplicationScoped
public class HousekeepingService {

    private final GitHubRepositoryIndexer indexer;
    private final DependencyTrackClient dtrackClient;
    private final HousekeepingWorker worker;
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
        return Uni.createFrom().deferred(() -> doRun(selection));
    }

    private Uni<Void> doRun(HousekeepingSelection selection) {
        if (!running.compareAndSet(false, true)) {
            Log.warn("Housekeeping run skipped, the previous one is still in progress");
            return Uni.createFrom().voidItem();
        }

        final List<String> enabledActions;
        try {
            enabledActions = worker.enabledActions();
        } catch (RuntimeException e) {
            running.set(false);
            Log.error("Housekeeping run aborted, could not determine enabled actions", e);
            return Uni.createFrom().voidItem();
        }
        if (enabledActions.isEmpty()) {
            running.set(false);
            Log.info("Housekeeping run skipped, no action enabled");
            return Uni.createFrom().voidItem();
        }

        var run = new HousekeepingRun(UUID.randomUUID().toString().substring(0, 8), Instant.now(), dryRun);
        var sample = Timer.start(meterRegistry);
        run.writeToMDC();
        Log.infof("Housekeeping run %s started%s with actions %s%s", run.id(), run.dryRun() ? " in dry-run mode" : "", enabledActions,
            selection.isAll() ? "" : ", restricted to repositories " + new TreeSet<>(selection.repositories()) + " and projects " + new TreeSet<>(selection.projects()));
        worker.begin(run);

        return crawl(run, selection)
            .invoke(summary -> reportCompletion(run, summary, selection))
            .onTermination().invoke(() -> {
                worker.end(run);
                sample.stop(meterRegistry.timer("housekeeping_run_duration"));
                running.set(false);
            })
            // safety net only, all steps above handle their failures
            .onFailure().invoke(failure -> Log.errorf(failure, "Housekeeping run %s failed", run.id()))
            .onFailure().recoverWithNull()
            .replaceWithVoid();
    }

    private Uni<HousekeepingSummary> crawl(HousekeepingRun run, HousekeepingSelection selection) {
        var progress = new AtomicReference<Supplier<HousekeepingSummary>>(HousekeepingSummary::notStarted);
        return Uni.combine().all().unis(indexer.build(run), dtrackClient.listProjects(false)).asTuple()
            .chain(listings -> {
                var index = listings.getItem1();
                var projects = listings.getItem2();
                run.writeToMDC();
                var allUnits = UnitMatcher.match(run, index, projects, enabledRepos);
                var units = allUnits.stream().filter(selection::selects).toList();
                if (!selection.isAll()) {
                    var unmatched = selection.unmatched(allUnits);
                    if (!unmatched.isEmpty()) {
                        Log.warnf("Nothing found for the selected %s", unmatched);
                    }
                }
                var failedUnits = new AtomicInteger();
                progress.set(() -> summarize(index, projects, units, failedUnits.get(), true));
                Log.infof("Housekeeping found %d repositories of %d GitHub accounts and %d projects in Dependency-Track, resulting in %d units to take care of%s",
                    index.repositories().size(), index.owners(), projects.size(), units.size(),
                    selection.isAll() ? "" : " (of %d in total)".formatted(allUnits.size()));

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
            run.id(), summary.complete() ? "completed" : "completed incompletely", Duration.between(run.startedAt(), Instant.now()), summary);
    }
}
