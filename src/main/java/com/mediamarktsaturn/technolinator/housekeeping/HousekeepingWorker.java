package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The one and only place where housekeeping happens. It's invoked once for every {@link HousekeepingUnit}, which holds a GitHub repository
 * and/or the Dependency-Track projects belonging to it, so everything that needs to be done for repositories or projects is done in here,
 * based on everything known about them.
 * <p>
 * The worker consists of actions. Each of them is switched on separately ({@code app.housekeeping.<action-name>.enabled}),
 * decides on its own whether it applies to a unit, and is isolated from the others: a failing action is logged and counted,
 * but neither affects the other actions nor other units.
 * To add housekeeping, add an action to the list in the constructor.
 * Actions have to respect {@link HousekeepingUnit#dryRun()}.
 * <p>
 * Actions:
 * <ul>
 *     <li>{@value #ARCHIVE_ORPHANED_PROJECTS}: archives the Dependency-Track projects, whose GitHub repository is archived or doesn't exist anymore</li>
 * </ul>
 */
@ApplicationScoped
public class HousekeepingWorker {

    public static final String ARCHIVE_ORPHANED_PROJECTS = "archive-orphaned-projects";

    private final DependencyTrackClient dtrackClient;
    private final MeterRegistry meterRegistry;
    private final Duration actionTimeout;
    private final int maxArchivedPerRun;
    private final List<Action> actions;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    @Inject
    public HousekeepingWorker(
        DependencyTrackClient dtrackClient,
        MeterRegistry meterRegistry,
        @ConfigProperty(name = "app.housekeeping.task_timeout") Duration actionTimeout,
        @ConfigProperty(name = "app.housekeeping.archive-orphaned-projects.enabled") boolean archiveOrphanedProjects,
        @ConfigProperty(name = "app.housekeeping.archive-orphaned-projects.max-per-run") int maxArchivedPerRun
    ) {
        this.dtrackClient = dtrackClient;
        this.meterRegistry = meterRegistry;
        this.actionTimeout = actionTimeout;
        this.maxArchivedPerRun = maxArchivedPerRun;
        this.actions = List.of(
            new Action(ARCHIVE_ORPHANED_PROJECTS, () -> archiveOrphanedProjects, this::isOrphanedProjectsUnit, this::archiveOrphanedProjects)
        );
    }

    /**
     * @return whether there's anything to do at all
     */
    public boolean enabled() {
        return actions.stream().anyMatch(action -> action.enabled.getAsBoolean());
    }

    public List<String> enabledActions() {
        return actions.stream().filter(action -> action.enabled.getAsBoolean()).map(action -> action.name).toList();
    }

    /**
     * Prepares the worker for the units of the [run]
     */
    public void begin(HousekeepingRun run) {
        runs.put(run.id(), new RunState());
    }

    /**
     * Reports what was done during the [run] and releases its state
     */
    public void end(HousekeepingRun run) {
        var state = runs.remove(run.id());
        if (state == null) {
            return;
        }
        run.writeToMDC();
        Log.infof("%s %d of %d projects, whose repository is archived or doesn't exist anymore",
            run.dryRun() ? "Would have archived" : "Archived", state.archived.get(), state.archiveCandidates.get());
    }

    /**
     * Performs all enabled actions on the unit. Never fails, but reports whether all actions succeeded.
     * Actions run in sequence on a worker thread, so they're free to do blocking calls.
     * Units of runs not in progress (before {@link #begin}, after {@link #end}) are skipped.
     */
    public Uni<Boolean> process(HousekeepingUnit unit) {
        Uni<Boolean> chain = Uni.createFrom().item(true);
        for (var action : actions) {
            if (action.enabled.getAsBoolean() && action.applies.test(unit)) {
                chain = chain.chain(allSucceeded -> invoke(action, unit).map(success -> allSucceeded && success));
            }
        }
        return chain;
    }

    private Uni<Boolean> invoke(Action action, HousekeepingUnit unit) {
        return Uni.createFrom().deferred(() -> {
                unit.writeToMDC();
                if (!runs.containsKey(unit.run().id())) {
                    // the run is over (e.g. timed out) while the unit was waiting for a thread, its state and limits are gone
                    Log.warnf("Housekeeping run %s is over, skipping %s for %s", unit.run().id(), action.name, unit.subject());
                    return Uni.createFrom().item(true);
                }
                return perform(action, unit);
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private Uni<Boolean> perform(Action action, HousekeepingUnit unit) {
        return Uni.createFrom().deferred(() -> action.work.apply(unit))
            .ifNoItem().after(actionTimeout).fail()
            .replaceWith(true)
            .onFailure().recoverWithItem(failure -> {
                unit.writeToMDC();
                Log.errorf(failure, "Housekeeping action %s failed for %s", action.name, unit.subject());
                return false;
            })
            .invoke(success -> meterRegistry.counter("housekeeping_action", "action", action.name, "status", success ? "OK" : "ERROR").increment());
    }

    /**
     * @return the state of the run, which exists as long as the run, see {@link #invoke}
     */
    private RunState state(HousekeepingRun run) {
        var state = runs.get(run.id());
        if (state == null) {
            throw new IllegalStateException("Housekeeping run %s is not in progress".formatted(run.id()));
        }
        return state;
    }

    private boolean isOrphanedProjectsUnit(HousekeepingUnit unit) {
        return unit.repositoryGoneOrArchived() && !unit.activeProjects().isEmpty();
    }

    /**
     * Archives the projects of a repository, that is archived or doesn't exist anymore. This covers repositories archived or deleted
     * while Technolinator didn't get to know (missed events, or event handling being disabled).
     * <p>
     * Whether a repository is gone is only said if that's certain, see {@link GitHubRepositoryIndex}, and projects without a (single) reference
     * to a repository are never touched, see {@link UnitMatcher}.
     * Further safeguards:
     * <ul>
     *     <li>units with a project having a BOM uploaded after the run started are ignored, they might belong to a brand-new repository</li>
     *     <li>no more than {@code max-per-run} projects are archived per run, to limit the damage of unforeseen circumstances.
     *     The limit is checked per unit, so the projects of the last repository might exceed it.</li>
     * </ul>
     * Archived projects get active again with the next BOM upload, so archiving is easily reverted.
     */
    private Uni<Void> archiveOrphanedProjects(HousekeepingUnit unit) {
        var run = unit.run();
        var state = state(run);
        var projects = unit.activeProjects();
        var reason = unit.repositoryState() == GitHubRepositoryIndex.State.GONE
            ? "doesn't exist anymore"
            : "is archived";
        var subject = unit.repository().map(repo -> "repository " + repo.fullName()).orElseGet(unit::subject);

        var recentlyUploaded = projects.stream().filter(project -> project.lastBomImport().filter(imported -> imported.isAfter(run.startedAt())).isPresent()).toList();
        if (!recentlyUploaded.isEmpty()) {
            Log.infof("%s %s, but project %s got a BOM uploaded since the run started, ignoring",
                subject, reason, recentlyUploaded.getFirst().displayName());
            return Uni.createFrom().voidItem();
        }

        var candidatesBefore = state.archiveCandidates.getAndAdd(projects.size());
        if (candidatesBefore >= maxArchivedPerRun) {
            if (!state.limitReported.getAndSet(true)) {
                Log.warnf("Limit of %d archived projects per run reached, leaving the remaining ones for the next run", maxArchivedPerRun);
            }
            return Uni.createFrom().voidItem();
        }

        if (unit.dryRun()) {
            state.archived.addAndGet(projects.size());
            Log.infof("Would archive %d project(s) of %s, which %s: %s", projects.size(), subject, reason,
                projects.stream().map(DependencyTrackProject::displayName).toList());
            return Uni.createFrom().voidItem();
        }

        Log.infof("Archiving %d project(s) of %s, which %s", projects.size(), subject, reason);
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (var wave : unit.activeProjectsChildrenFirst()) {
            chain = chain.chain(() -> archiveWave(wave, state));
        }
        return chain;
    }

    private Uni<Void> archiveWave(List<DependencyTrackProject> wave, RunState state) {
        return Uni.combine().all().unis(
                wave.stream().map(project -> dtrackClient.archiveProject(project.uuid())).toList()
            ).with(Boolean.class, results -> (int) results.stream().filter(archived -> !archived).count())
            .invoke(failed -> state.archived.addAndGet(wave.size() - failed))
            .chain(failed -> failed == 0
                ? Uni.createFrom().voidItem()
                // parents can't be archived with active children, so the remaining waves are left for the next run
                : Uni.createFrom().failure(new IllegalStateException("Could not archive %d of %d projects".formatted(failed, wave.size()))));
    }

    private record Action(
        String name,
        BooleanSupplier enabled,
        Predicate<HousekeepingUnit> applies,
        Function<HousekeepingUnit, Uni<Void>> work
    ) {
    }

    private static final class RunState {
        final AtomicInteger archiveCandidates = new AtomicInteger();
        // in dry-run mode: would have been archived
        final AtomicInteger archived = new AtomicInteger();
        final AtomicBoolean limitReported = new AtomicBoolean();
    }
}
