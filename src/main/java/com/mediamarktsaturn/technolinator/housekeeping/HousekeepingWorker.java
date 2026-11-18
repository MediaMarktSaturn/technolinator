package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import com.mediamarktsaturn.technolinator.sbom.RepositoryProjectMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *     <li>{@value #REACTIVATE_PROJECTS}: reactivates archived Dependency-Track projects, that definitely belong to an active GitHub repository and aren't
 *     superseded by an active version, which repairs projects archived by mistake</li>
 *     <li>{@value #UPDATE_PROJECT_METADATA}: backfills the external references and tags of the projects definitely belonging to an active repository,
 *     the way the BOM upload sets them</li>
 * </ul>
 */
@ApplicationScoped
public class HousekeepingWorker {

    public static final String ARCHIVE_ORPHANED_PROJECTS = "archive-orphaned-projects";
    public static final String UPDATE_PROJECT_METADATA = "update-project-metadata";
    public static final String REACTIVATE_PROJECTS = "reactivate-projects";

    private final DependencyTrackClient dtrackClient;
    private final GitHubRepositoryTopics repositoryTopics;
    private final MeterRegistry meterRegistry;
    private final Duration actionTimeout;
    private final int maxArchivedPerRun;
    private final boolean updateProjectMetadata;
    private final int maxMetadataUpdatesPerRun;
    private final boolean reactivateProjects;
    private final int maxReactivatedPerRun;
    private final List<Action> actions;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    @Inject
    public HousekeepingWorker(
        DependencyTrackClient dtrackClient,
        GitHubRepositoryTopics repositoryTopics,
        MeterRegistry meterRegistry,
        @ConfigProperty(name = "app.housekeeping.task_timeout") Duration actionTimeout,
        @ConfigProperty(name = "app.housekeeping.archive-orphaned-projects.enabled") boolean archiveOrphanedProjects,
        @ConfigProperty(name = "app.housekeeping.archive-orphaned-projects.max-per-run") int maxArchivedPerRun,
        @ConfigProperty(name = "app.housekeeping.update-project-metadata.enabled") boolean updateProjectMetadata,
        @ConfigProperty(name = "app.housekeeping.update-project-metadata.max-per-run") int maxMetadataUpdatesPerRun,
        @ConfigProperty(name = "app.housekeeping.reactivate-projects.enabled") boolean reactivateProjects,
        @ConfigProperty(name = "app.housekeeping.reactivate-projects.max-per-run") int maxReactivatedPerRun
    ) {
        this.dtrackClient = dtrackClient;
        this.repositoryTopics = repositoryTopics;
        this.meterRegistry = meterRegistry;
        this.actionTimeout = actionTimeout;
        this.maxArchivedPerRun = maxArchivedPerRun;
        this.updateProjectMetadata = updateProjectMetadata;
        this.maxMetadataUpdatesPerRun = maxMetadataUpdatesPerRun;
        this.reactivateProjects = reactivateProjects;
        this.maxReactivatedPerRun = maxReactivatedPerRun;
        this.actions = List.of(
            new Action(ARCHIVE_ORPHANED_PROJECTS, () -> archiveOrphanedProjects, this::isOrphanedProjectsUnit, this::archiveOrphanedProjects),
            new Action(REACTIVATE_PROJECTS, () -> reactivateProjects, this::hasInactiveProjectsOfActiveRepository, this::reactivateProjects),
            new Action(UPDATE_PROJECT_METADATA, () -> updateProjectMetadata, this::isActiveRepositoryUnit, this::updateProjectMetadata)
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
        if (reactivateProjects) {
            Log.infof("%s %d of %d archived projects of active repositories",
                run.dryRun() ? "Would have reactivated" : "Reactivated", state.reactivated.get(), state.reactivationCandidates.get());
        }
        if (updateProjectMetadata) {
            Log.infof("%s %d of %d projects lacking references or tags of their repository",
                run.dryRun() ? "Would have updated" : "Updated", state.metadataUpdated.get(), state.metadataCandidates.get());
        }
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

    private boolean isActiveRepositoryUnit(HousekeepingUnit unit) {
        return unit.repositoryState() == GitHubRepositoryIndex.State.ACTIVE && !unit.projects().isEmpty();
    }

    /**
     * Adds the references to the repository and the tags (owner, topics) to the projects belonging to an active repository, that lack them,
     * which is the case for projects created before Technolinator set them, or by other means.
     * <p>
     * Only projects definitely belonging to the repository are touched, which are the ones identified by the same rules as the BOM upload
     * does ({@link RepositoryProjectMatcher#select}: by reference, or by their name if they don't have any). Projects matched by being part of a
     * unit only are left alone, and so are the ones claimed by multiple repositories, see {@link UnitMatcher}.
     * <p>
     * Only projects lacking the references to the repository or its owner tag are looked at, the others are up to date as far as housekeeping is
     * concerned, so GitHub is only asked for topics when there's something to do. Topics changed on GitHub reach the projects with their next BOM upload.
     * Before changing, the project is fetched again, to not overwrite tags that changed meanwhile (e.g. by an upload during the run).
     * Only references and tags are sent, so neither the description nor the state of a project (archived ones stay archived) are touched.
     * As with the upload, references and tags are replaced by the desired ones, though the ones the upload derives from analysis
     * (the form {@code key=value}, see {@link ProjectMetadata}) are kept, and so are references of other types.
     * <p>
     * No more than {@code max-per-run} projects are looked at per run, which spreads the backfill over multiple runs.
     * The limit is checked per unit, so the projects of the last repository might exceed it.
     */
    private Uni<Void> updateProjectMetadata(HousekeepingUnit unit) {
        var state = state(unit.run());
        var repository = unit.repository().orElseThrow();
        var candidates = RepositoryProjectMatcher.select(unit.projects(), repository.name(), repository.gitUrl(), repository.htmlUrl()).stream()
            .filter(DependencyTrackProject::active)
            .filter(project -> ProjectMetadata.lacksRepositoryMetadata(project, repository))
            .toList();
        if (candidates.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        var candidatesBefore = state.metadataCandidates.getAndAdd(candidates.size());
        if (candidatesBefore >= maxMetadataUpdatesPerRun) {
            if (!state.metadataLimitReported.getAndSet(true)) {
                Log.warnf("Limit of %d updated projects per run reached, leaving the remaining ones for the next run", maxMetadataUpdatesPerRun);
            }
            return Uni.createFrom().voidItem();
        }

        List<String> topics;
        try {
            topics = repositoryTopics.topics(repository);
        } catch (IOException e) {
            return Uni.createFrom().failure(new IllegalStateException("Could not fetch topics of repository " + repository.fullName(), e));
        }

        if (unit.dryRun()) {
            var updates = candidates.stream()
                .filter(project -> ProjectMetadata.requiredUpdate(project, repository, topics).isPresent())
                .map(DependencyTrackProject::displayName)
                .toList();
            state.metadataUpdated.addAndGet(updates.size());
            if (!updates.isEmpty()) {
                Log.infof("Would update references and tags of %d project(s) of repository %s: %s", updates.size(), repository.fullName(), updates);
            }
            return Uni.createFrom().voidItem();
        }

        Log.infof("Updating references and tags of %d project(s) of repository %s", candidates.size(), repository.fullName());
        Uni<Integer> failures = Uni.createFrom().item(0);
        for (var candidate : candidates) {
            failures = failures.chain(failed -> updateMetadata(candidate, repository, topics, state).map(success -> success ? failed : failed + 1));
        }
        return failures.chain(failed -> failed == 0
            ? Uni.createFrom().voidItem()
            : Uni.createFrom().failure(new IllegalStateException("Could not update %d of %d projects".formatted(failed, candidates.size()))));
    }

    private Uni<Boolean> updateMetadata(DependencyTrackProject listed, GitHubRepo repository, List<String> topics, RunState state) {
        return dtrackClient.getProject(listed.uuid())
            .chain(current -> {
                if (current.isEmpty() || !current.get().active()) {
                    return Uni.createFrom().item(true);
                }
                var update = ProjectMetadata.requiredUpdate(current.get(), repository, topics);
                if (update.isEmpty()) {
                    return Uni.createFrom().item(true);
                }
                return dtrackClient.updateProjectReferencesAndTags(listed.uuid(), update.get().references(), update.get().tags())
                    .invoke(updated -> {
                        if (updated) {
                            state.metadataUpdated.incrementAndGet();
                        }
                    });
            })
            .onFailure().recoverWithItem(failure -> {
                Log.warnf(failure, "Could not update references and tags of project %s", listed.displayName());
                return false;
            });
    }

    private boolean hasInactiveProjectsOfActiveRepository(HousekeepingUnit unit) {
        return isActiveRepositoryUnit(unit) && unit.projects().stream().anyMatch(project -> !project.active());
    }

    /**
     * Reactivates archived projects of an active repository, which repairs projects archived by mistake, e.g. by faulty repository data
     * or by archiving a repository that's unarchived since.
     * <p>
     * Archived projects are the normal case for old versions: the BOM upload archives all other versions of a project, so only one version
     * of every project is expected to be active. Hence only projects definitely belonging to the repository ({@link RepositoryProjectMatcher#select},
     * same as for the metadata) are considered, and only if no version of the same name is active. Of several archived versions, the one with the
     * latest BOM import is reactivated, none if that can't be told.
     * Parents are reactivated before their children, and children of a parent staying archived stay archived as well.
     * <p>
     * Mind that this reverts projects archived on purpose, too, while their repository is active. Switch the action off, if that's not wanted.
     * No more than {@code max-per-run} projects are reactivated per run, checked per unit, so the last repository's projects might exceed it.
     */
    private Uni<Void> reactivateProjects(HousekeepingUnit unit) {
        var state = state(unit.run());
        var repository = unit.repository().orElseThrow();
        var candidates = projectsToReactivate(unit, repository);
        if (candidates.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        var candidatesBefore = state.reactivationCandidates.getAndAdd(candidates.size());
        if (candidatesBefore >= maxReactivatedPerRun) {
            if (!state.reactivationLimitReported.getAndSet(true)) {
                Log.warnf("Limit of %d reactivated projects per run reached, leaving the remaining ones for the next run", maxReactivatedPerRun);
            }
            return Uni.createFrom().voidItem();
        }

        if (unit.dryRun()) {
            state.reactivated.addAndGet(candidates.size());
            Log.infof("Would reactivate %d project(s) of active repository %s: %s", candidates.size(), repository.fullName(),
                candidates.stream().map(DependencyTrackProject::displayName).toList());
            return Uni.createFrom().voidItem();
        }

        Log.infof("Reactivating %d project(s) of active repository %s", candidates.size(), repository.fullName());
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (var wave : unit.groupParentsFirst(candidates)) {
            chain = chain.chain(() -> reactivateWave(wave, state));
        }
        return chain;
    }

    private static List<DependencyTrackProject> projectsToReactivate(HousekeepingUnit unit, GitHubRepo repository) {
        var byName = new LinkedHashMap<String, List<DependencyTrackProject>>();
        RepositoryProjectMatcher.select(unit.projects(), repository.name(), repository.gitUrl(), repository.htmlUrl())
            .forEach(project -> byName.computeIfAbsent(project.name(), name -> new ArrayList<>()).add(project));

        var candidates = new LinkedHashMap<String, DependencyTrackProject>();
        for (var versions : byName.values()) {
            if (versions.stream().anyMatch(DependencyTrackProject::active)) {
                continue;
            }
            if (versions.size() == 1) {
                candidates.put(versions.getFirst().uuid(), versions.getFirst());
            } else {
                versions.stream()
                    .filter(version -> version.lastBomImport().isPresent())
                    .max(Comparator.comparing(version -> version.lastBomImport().orElseThrow()))
                    .ifPresent(latest -> candidates.put(latest.uuid(), latest));
            }
        }

        // children are better left archived than active below a parent staying archived, which cascades down the hierarchy
        var archived = new HashMap<String, DependencyTrackProject>();
        unit.projects().stream().filter(project -> !project.active()).forEach(project -> archived.put(project.uuid(), project));
        boolean removed;
        do {
            removed = candidates.values().removeIf(candidate -> candidate.parentUuid()
                .filter(parent -> archived.containsKey(parent) && !candidates.containsKey(parent))
                .isPresent());
        } while (removed);
        return List.copyOf(candidates.values());
    }

    private Uni<Void> reactivateWave(List<DependencyTrackProject> wave, RunState state) {
        return Uni.combine().all().unis(
                wave.stream().map(project -> dtrackClient.reactivateProject(project.uuid())).toList()
            ).with(Boolean.class, results -> (int) results.stream().filter(reactivated -> !reactivated).count())
            .invoke(failed -> state.reactivated.addAndGet(wave.size() - failed))
            .chain(failed -> failed == 0
                ? Uni.createFrom().voidItem()
                // children are better left archived than active below an archived parent
                : Uni.createFrom().failure(new IllegalStateException("Could not reactivate %d of %d projects".formatted(failed, wave.size()))));
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
        final AtomicInteger metadataCandidates = new AtomicInteger();
        // in dry-run mode: would have been updated
        final AtomicInteger metadataUpdated = new AtomicInteger();
        final AtomicBoolean metadataLimitReported = new AtomicBoolean();
        final AtomicInteger reactivationCandidates = new AtomicInteger();
        // in dry-run mode: would have been reactivated
        final AtomicInteger reactivated = new AtomicInteger();
        final AtomicBoolean reactivationLimitReported = new AtomicBoolean();
    }
}
