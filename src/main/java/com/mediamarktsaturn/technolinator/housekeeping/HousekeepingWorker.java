package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.Change;
import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.ConfiguredModules;
import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.Reason;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import com.mediamarktsaturn.technolinator.sbom.RepositoryProjectMatcher;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The one and only place where housekeeping happens. It's invoked once for every {@link HousekeepingUnit}, which holds a GitHub repository
 * and/or the Dependency-Track projects belonging to it, so everything that needs to be done for repositories or projects is done in here,
 * based on everything known about them.
 * <p>
 * The worker consists of actions, performed in this order. Each of them is switched on separately, decides on its own whether it applies to a unit,
 * and is isolated from the others: a failing action is logged and counted, but neither affects the other actions nor other units.
 * To add housekeeping, add an action to the list in the constructor. Actions have to respect {@link HousekeepingUnit#dryRun()}.
 * <ul>
 *     <li>{@value #FOLLOW_RENAMED_REPOSITORIES}: renames the Dependency-Track projects of a repository that got renamed (and updates their references),
 *     for the cases where the rename event wasn't handled</li>
 *     <li>{@value #SYNC_PROJECT_STATES}: archives and reactivates Dependency-Track projects, so their state matches their repository, by the rules
 *     {@code archive-orphaned-projects}, {@code archive-removed-modules} and {@code reactivate-projects}, see {@link ProjectStatePlanner}</li>
 *     <li>{@value #UPDATE_PROJECT_METADATA}: backfills the external references and tags of the projects definitely belonging to an active repository,
 *     the way the BOM upload sets them</li>
 * </ul>
 * Changes of the state of projects all go through the {@link ProjectStateChanger}, and every kind of change is limited per run, see {@link RunBudget}.
 */
@ApplicationScoped
public class HousekeepingWorker {

    public static final String FOLLOW_RENAMED_REPOSITORIES = "follow-renamed-repositories";
    public static final String SYNC_PROJECT_STATES = "sync-project-states";
    public static final String UPDATE_PROJECT_METADATA = "update-project-metadata";

    /**
     * A switch, and the limit of projects changed per run
     */
    public record Feature(boolean enabled, int maxPerRun) {

        public static Feature off() {
            return new Feature(false, 0);
        }

        public static Feature on(int maxPerRun) {
            return new Feature(true, maxPerRun);
        }
    }

    /**
     * @param actionTimeout             Maximum duration of a single action for a unit
     * @param followRenamedRepositories The action {@value #FOLLOW_RENAMED_REPOSITORIES}, limiting the projects looked at
     * @param archiveOrphanedProjects   Rule of {@value #SYNC_PROJECT_STATES}: archive the projects of archived or deleted repositories
     * @param archiveRemovedModules     Rule of {@value #SYNC_PROJECT_STATES}: archive the child projects of modules removed from an active repository
     * @param reactivateProjects        Rule of {@value #SYNC_PROJECT_STATES}: reactivate archived projects of an active repository
     * @param updateProjectMetadata     The action {@value #UPDATE_PROJECT_METADATA}, limiting the projects looked at
     */
    public record Settings(
        Duration actionTimeout,
        Feature followRenamedRepositories,
        Feature archiveOrphanedProjects,
        Feature archiveRemovedModules,
        Feature reactivateProjects,
        Feature updateProjectMetadata
    ) {
    }

    private final DependencyTrackClient dtrackClient;
    private final GitHubRepositoryTopics repositoryTopics;
    private final GitHubRepositoryConfig repositoryConfig;
    private final MeterRegistry meterRegistry;
    private final Settings settings;
    private final ProjectStatePlanner.Rules stateRules;
    private final ProjectStateChanger stateChanger;
    private final List<Action> actions;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    @Inject
    public HousekeepingWorker(
        DependencyTrackClient dtrackClient,
        GitHubRepositoryTopics repositoryTopics,
        GitHubRepositoryConfig repositoryConfig,
        MeterRegistry meterRegistry,
        @ConfigProperty(name = "app.housekeeping.task_timeout") Duration actionTimeout,
        @ConfigProperty(name = "app.housekeeping.follow-renamed-repositories.enabled") boolean followRenamedRepositories,
        @ConfigProperty(name = "app.housekeeping.follow-renamed-repositories.max-per-run") int maxRenamedPerRun,
        @ConfigProperty(name = "app.housekeeping.archive-orphaned-projects.enabled") boolean archiveOrphanedProjects,
        @ConfigProperty(name = "app.housekeeping.archive-orphaned-projects.max-per-run") int maxArchivedPerRun,
        @ConfigProperty(name = "app.housekeeping.archive-removed-modules.enabled") boolean archiveRemovedModules,
        @ConfigProperty(name = "app.housekeeping.archive-removed-modules.max-per-run") int maxRemovedModulesPerRun,
        @ConfigProperty(name = "app.housekeeping.reactivate-projects.enabled") boolean reactivateProjects,
        @ConfigProperty(name = "app.housekeeping.reactivate-projects.max-per-run") int maxReactivatedPerRun,
        @ConfigProperty(name = "app.housekeeping.update-project-metadata.enabled") boolean updateProjectMetadata,
        @ConfigProperty(name = "app.housekeeping.update-project-metadata.max-per-run") int maxMetadataUpdatesPerRun
    ) {
        this(dtrackClient, repositoryTopics, repositoryConfig, meterRegistry, new Settings(
            actionTimeout,
            new Feature(followRenamedRepositories, maxRenamedPerRun),
            new Feature(archiveOrphanedProjects, maxArchivedPerRun),
            new Feature(archiveRemovedModules, maxRemovedModulesPerRun),
            new Feature(reactivateProjects, maxReactivatedPerRun),
            new Feature(updateProjectMetadata, maxMetadataUpdatesPerRun)
        ));
    }

    HousekeepingWorker(
        DependencyTrackClient dtrackClient,
        GitHubRepositoryTopics repositoryTopics,
        GitHubRepositoryConfig repositoryConfig,
        MeterRegistry meterRegistry,
        Settings settings
    ) {
        this.dtrackClient = dtrackClient;
        this.repositoryTopics = repositoryTopics;
        this.repositoryConfig = repositoryConfig;
        this.meterRegistry = meterRegistry;
        this.settings = settings;
        this.stateRules = new ProjectStatePlanner.Rules(
            settings.archiveOrphanedProjects().enabled(),
            settings.archiveRemovedModules().enabled(),
            settings.reactivateProjects().enabled()
        );
        this.stateChanger = new ProjectStateChanger(dtrackClient);
        this.actions = List.of(
            new Action(FOLLOW_RENAMED_REPOSITORIES, settings.followRenamedRepositories().enabled(), this::isRenamedRepositoryUnit, this::followRenamedRepository),
            new Action(SYNC_PROJECT_STATES, stateRules.any(), unit -> ProjectStatePlanner.mightApply(unit, stateRules), this::syncProjectStates),
            new Action(UPDATE_PROJECT_METADATA, settings.updateProjectMetadata().enabled(), this::isActiveRepositoryUnit, this::updateProjectMetadata)
        );
    }

    /**
     * @return whether there's anything to do at all
     */
    public boolean enabled() {
        return actions.stream().anyMatch(Action::enabled);
    }

    public List<String> enabledActions() {
        return actions.stream().filter(Action::enabled).map(Action::name).toList();
    }

    /**
     * Prepares the worker for the units of the [run]
     */
    public void begin(HousekeepingRun run) {
        runs.put(run.id(), new RunState(settings));
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
        var dry = run.dryRun();
        if (settings.followRenamedRepositories().enabled()) {
            Log.infof("%s %d projects of renamed repositories, %d archived instead, as the new name is taken already%s",
                dry ? "Would have renamed" : "Renamed", state.renamed.get(), state.changed(Reason.SUPERSEDED), deferred(state.renameBudget));
        }
        if (stateRules.any()) {
            Log.infof("%s %d projects of archived repositories, %d of deleted repositories and %d of removed modules, %s %d projects of active repositories%s%s%s",
                dry ? "Would have archived" : "Archived", state.changed(Reason.REPOSITORY_ARCHIVED), state.changed(Reason.REPOSITORY_GONE),
                state.changed(Reason.MODULE_REMOVED), dry ? "would have reactivated" : "reactivated", state.changed(Reason.REPOSITORY_ACTIVE),
                deferred(state.orphanedBudget), deferred(state.removedModulesBudget), deferred(state.reactivationBudget));
        }
        if (settings.updateProjectMetadata().enabled()) {
            Log.infof("%s %d projects lacking references or tags of their repository%s",
                dry ? "Would have updated" : "Updated", state.metadataUpdated.get(), deferred(state.metadataBudget));
        }
    }

    private static String deferred(RunBudget budget) {
        return budget.deferred() == 0 ? "" : "; %d %s left for the next run by the limit of %d".formatted(budget.deferred(), budget.subject(), budget.max());
    }

    /**
     * Performs all enabled actions on the unit. Never fails, but reports whether all actions succeeded.
     * Actions run in sequence on a worker thread, so they're free to do blocking calls.
     * Units of runs not in progress (before {@link #begin}, after {@link #end}) are skipped.
     */
    public Uni<Boolean> process(HousekeepingUnit unit) {
        Uni<Boolean> chain = Uni.createFrom().item(true);
        for (var action : actions) {
            if (action.enabled() && action.applies().test(unit)) {
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
                    Log.warnf("Housekeeping run %s is over, skipping %s for %s", unit.run().id(), action.name(), unit.subject());
                    return Uni.createFrom().item(true);
                }
                return perform(action, unit);
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    private Uni<Boolean> perform(Action action, HousekeepingUnit unit) {
        return Uni.createFrom().deferred(() -> action.work().apply(unit))
            .ifNoItem().after(settings.actionTimeout()).fail()
            .replaceWith(true)
            .onFailure().recoverWithItem(failure -> {
                unit.writeToMDC();
                Log.errorf(failure, "Housekeeping action %s failed for %s", action.name(), unit.subject());
                if (!(failure instanceof ProjectsFailedException)) {
                    HousekeepingActionLog.unitFailed(action.name(), unit);
                }
                return false;
            })
            .invoke(success -> meterRegistry.counter("housekeeping_action", "action", action.name(), "status", success ? "OK" : "ERROR").increment());
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

    private boolean isRenamedRepositoryUnit(HousekeepingUnit unit) {
        return unit.moved().isPresent();
    }

    /**
     * Lets the projects of a repository follow it, if it was renamed: renames them (the projects named like the repository, or starting with its
     * name followed by a dash, which are the modules) and updates their references to the repository, the way the handling of the rename event does.
     * This covers renames Technolinator didn't get to know (missed events, or event handling being disabled).
     * Otherwise, the old projects would stay active forever, and the next analysis would create new ones next to them, so every run would have to
     * look up the old repository again. After this, the projects reference the repository by its new name.
     * <p>
     * Only the projects referencing the old repository are looked at, see {@link UnitMatcher}. Transfers to another account aren't followed,
     * for GitHub they're gone, see {@link GitHubRepositoryIndex}.
     * Projects that can't be renamed because the name is taken in the same version already (the repository was analyzed under its new name since)
     * are superseded and get archived instead, see {@link ProjectStateChanger}. Anything else failing is left for the next run.
     * <p>
     * No more than {@code max-per-run} projects are looked at per run, a unit not fitting completely is left for the next run, see {@link RunBudget}.
     */
    private Uni<Void> followRenamedRepository(HousekeepingUnit unit) {
        var state = state(unit.run());
        var move = unit.moved().orElseThrow();
        var target = move.to();
        var oldName = move.from().name();
        var projects = unit.projects();

        if (!state.renameBudget.tryReserve(projects.size(), unit.subject())) {
            return Uni.createFrom().voidItem();
        }

        if (unit.dryRun()) {
            var changing = projects.stream()
                .filter(project -> startsWithName(project.name(), oldName) || UnitMatcher.referencesOf(project).contains(move.from()))
                .toList();
            state.renamed.addAndGet(changing.size());
            if (!changing.isEmpty()) {
                Log.infof("Would rename %d project(s) of repository %s, which was renamed to %s: %s", changing.size(), move.from(), target.fullName(),
                    changing.stream().map(DependencyTrackProject::displayName).toList());
                changing.forEach(project -> HousekeepingActionLog.project(FOLLOW_RENAMED_REPOSITORIES, true, unit, project,
                    "change", "rename", "new_name", renamed(project.name(), oldName, target.name())));
            }
            return Uni.createFrom().voidItem();
        }

        Log.infof("Renaming %d project(s) of repository %s, which was renamed to %s", projects.size(), move.from(), target.fullName());
        var oldVcsUrl = replaceLastSegment(target.gitUrl(), target.name(), oldName);
        var oldWebsiteUrl = replaceLastSegment(target.htmlUrl(), target.name(), oldName);
        return Multi.createFrom().iterable(projects)
            .onItem().transformToUni(project -> rename(unit, project, oldName, target, oldVcsUrl, oldWebsiteUrl).map(status -> Map.entry(project, status)))
            .merge(ProjectStateChanger.CONCURRENCY)
            .collect().asList()
            .chain(results -> {
                var superseded = new ArrayList<Change>();
                var failed = 0;
                for (var result : results) {
                    switch (result.getValue()) {
                        case RENAMED -> state.renamed.incrementAndGet();
                        // archived projects don't need to be superseded
                        case CONFLICT -> {
                            if (result.getKey().active()) {
                                superseded.add(new Change(result.getKey(), Reason.SUPERSEDED));
                            }
                        }
                        case FAILED -> failed++;
                        case UNCHANGED -> {
                        }
                    }
                }
                var renameFailures = failed;
                return stateChanger.apply(FOLLOW_RENAMED_REPOSITORIES, unit, superseded, state.stateChanges)
                    .map(outcome -> renameFailures + outcome.failed());
            })
            .chain(failed -> failed == 0
                ? Uni.createFrom().voidItem()
                : Uni.createFrom().failure(new ProjectsFailedException("Could not rename %d of %d projects".formatted(failed, projects.size()))));
    }

    private Uni<DependencyTrackClient.RenameStatus> rename(HousekeepingUnit unit, DependencyTrackProject project, String oldName, GitHubRepo target,
                                                           String oldVcsUrl, String oldWebsiteUrl) {
        var newName = renamed(project.name(), oldName, target.name());
        return Uni.createFrom().deferred(() -> dtrackClient.renameProjectOfRepository(project, oldName, target.name(), oldVcsUrl, oldWebsiteUrl, target.gitUrl(), target.htmlUrl()))
            .onFailure().recoverWithItem(failure -> {
                unit.writeToMDC();
                Log.warnf(failure, "Housekeeping action %s failed for project %s", FOLLOW_RENAMED_REPOSITORIES, project.displayName());
                return DependencyTrackClient.RenameStatus.FAILED;
            })
            .invoke(status -> {
                unit.writeToMDC();
                switch (status) {
                    case RENAMED -> HousekeepingActionLog.project(FOLLOW_RENAMED_REPOSITORIES, HousekeepingActionLog.Status.DONE, unit, project,
                        "change", "rename", "new_name", newName);
                    case FAILED -> HousekeepingActionLog.project(FOLLOW_RENAMED_REPOSITORIES, HousekeepingActionLog.Status.FAILED, unit, project,
                        "change", "rename", "new_name", newName);
                    // nothing was done, a conflict is dealt with afterwards
                    case UNCHANGED, CONFLICT -> {
                    }
                }
            });
    }

    private static boolean startsWithName(String projectName, String repositoryName) {
        return projectName != null && projectName.regionMatches(true, 0, repositoryName, 0, repositoryName.length());
    }

    private static String renamed(String projectName, String oldName, String newName) {
        return startsWithName(projectName, oldName) ? newName + projectName.substring(oldName.length()) : projectName;
    }

    private static String replaceLastSegment(String url, String newName, String oldName) {
        var needle = "/" + newName;
        var index = url.lastIndexOf(needle);
        if (index < 0) {
            return url;
        }
        return url.substring(0, index) + "/" + oldName + url.substring(index + needle.length());
    }

    /**
     * Archives and reactivates the projects of the unit, so their state matches the one of their repository, see {@link ProjectStatePlanner} for the rules.
     * All rules are decided on at once, so they can't work against each other, and the repository's configuration is read once at most
     * (only if needed: for removed modules, if the repository has child projects). Nothing happens if it can't be read, the unit fails.
     * <p>
     * Every rule has its own {@code max-per-run}, which is strict: the projects of a unit that don't fit completely are left for the next run.
     * The changes of the other rules are made nevertheless, which is consistent, as reactivations never depend on archiving and vice versa.
     * Before changing anything, the state of the projects is checked again, see {@link ProjectStateChanger}.
     * <p>
     * Archiving is reverted by the next BOM upload of a project, which reactivates it.
     */
    private Uni<Void> syncProjectStates(HousekeepingUnit unit) {
        var state = state(unit.run());
        Optional<ConfiguredModules> modules = Optional.empty();
        if (ProjectStatePlanner.needsConfiguration(unit, stateRules)) {
            var repository = unit.repository().orElseThrow();
            try {
                modules = Optional.of(ConfiguredModules.of(repository.name(), repositoryConfig.config(repository)));
            } catch (IOException e) {
                return Uni.createFrom().failure(new IllegalStateException("Could not read the configuration of repository " + repository.fullName(), e));
            }
            if (!modules.get().enabled()) {
                Log.debugf("Analysis of repository %s is disabled by its configuration, leaving its child projects alone", repository.fullName());
            }
        }

        var planned = ProjectStatePlanner.plan(unit, stateRules, modules);
        var reservation = reserve(unit, planned, state);
        if (reservation.changes().isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        return stateChanger.apply(SYNC_PROJECT_STATES, unit, reservation.changes(), state.stateChanges)
            .chain(outcome -> {
                if (outcome.skipped()) {
                    reservation.release();
                }
                return outcome.failed() == 0
                    ? Uni.createFrom().voidItem()
                    : Uni.createFrom().failure(new ProjectsFailedException("Could not change the state of %d of %d projects".formatted(outcome.failed(), reservation.changes().size())));
            });
    }

    /**
     * The changes that fit into the budgets of their rules
     */
    private record Reservation(List<Change> changes, Map<RunBudget, Integer> reserved) {

        void release() {
            reserved.forEach(RunBudget::release);
        }
    }

    private static Reservation reserve(HousekeepingUnit unit, List<Change> planned, RunState state) {
        var byBudget = new LinkedHashMap<RunBudget, List<Change>>();
        planned.forEach(change -> byBudget.computeIfAbsent(state.budgetOf(change.reason()), budget -> new ArrayList<>()).add(change));
        var changes = new ArrayList<Change>();
        var reserved = new LinkedHashMap<RunBudget, Integer>();
        byBudget.forEach((budget, ofBudget) -> {
            if (budget.tryReserve(ofBudget.size(), unit.subject())) {
                changes.addAll(ofBudget);
                reserved.put(budget, ofBudget.size());
            }
        });
        return new Reservation(List.copyOf(changes), reserved);
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
     */
    private Uni<Void> updateProjectMetadata(HousekeepingUnit unit) {
        var state = state(unit.run());
        var repository = unit.repository().orElseThrow();
        var lacking = RepositoryProjectMatcher.select(unit.projects(), repository.name(), repository.gitUrl(), repository.htmlUrl()).stream()
            .filter(DependencyTrackProject::active)
            .filter(project -> ProjectMetadata.lacksRepositoryMetadata(project, repository))
            .toList();
        if (lacking.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        // projects are independent of each other, so a part of them is fine
        var candidates = lacking.subList(0, state.metadataBudget.reserveUpTo(lacking.size(), unit.subject()));
        if (candidates.isEmpty()) {
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
                .toList();
            state.metadataUpdated.addAndGet(updates.size());
            if (!updates.isEmpty()) {
                Log.infof("Would update references and tags of %d project(s) of repository %s: %s", updates.size(), repository.fullName(),
                    updates.stream().map(DependencyTrackProject::displayName).toList());
                updates.forEach(project -> HousekeepingActionLog.project(UPDATE_PROJECT_METADATA, true, unit, project));
            }
            return Uni.createFrom().voidItem();
        }

        Log.infof("Updating references and tags of %d project(s) of repository %s", candidates.size(), repository.fullName());
        Uni<Integer> failures = Uni.createFrom().item(0);
        for (var candidate : candidates) {
            failures = failures.chain(failed -> updateMetadata(unit, candidate, repository, topics, state).map(success -> success ? failed : failed + 1));
        }
        return failures.chain(failed -> failed == 0
            ? Uni.createFrom().voidItem()
            : Uni.createFrom().failure(new ProjectsFailedException("Could not update %d of %d projects".formatted(failed, candidates.size()))));
    }

    private Uni<Boolean> updateMetadata(HousekeepingUnit unit, DependencyTrackProject listed, GitHubRepo repository, List<String> topics, RunState state) {
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
                    })
                    .invoke(updated -> {
                        unit.writeToMDC();
                        HousekeepingActionLog.project(UPDATE_PROJECT_METADATA,
                            updated ? HousekeepingActionLog.Status.DONE : HousekeepingActionLog.Status.FAILED, unit, listed);
                    });
            })
            .onFailure().recoverWithItem(failure -> {
                unit.writeToMDC();
                Log.warnf(failure, "Could not update references and tags of project %s", listed.displayName());
                HousekeepingActionLog.project(UPDATE_PROJECT_METADATA, HousekeepingActionLog.Status.FAILED, unit, listed);
                return false;
            });
    }

    /**
     * Failure of an action because of projects it couldn't change, which are logged on their own already.
     */
    private static final class ProjectsFailedException extends IllegalStateException {
        ProjectsFailedException(String message) {
            super(message);
        }
    }

    private record Action(
        String name,
        boolean enabled,
        Predicate<HousekeepingUnit> applies,
        Function<HousekeepingUnit, Uni<Void>> work
    ) {
    }

    private static final class RunState {
        final RunBudget renameBudget;
        final RunBudget orphanedBudget;
        final RunBudget removedModulesBudget;
        final RunBudget reactivationBudget;
        final RunBudget metadataBudget;
        // in dry-run mode: would have been changed
        final Map<Reason, AtomicInteger> stateChanges = new EnumMap<>(Reason.class);
        final AtomicInteger renamed = new AtomicInteger();
        final AtomicInteger metadataUpdated = new AtomicInteger();

        RunState(Settings settings) {
            renameBudget = new RunBudget("renamed projects", settings.followRenamedRepositories().maxPerRun());
            orphanedBudget = new RunBudget("archived projects of archived or deleted repositories", settings.archiveOrphanedProjects().maxPerRun());
            removedModulesBudget = new RunBudget("archived projects of removed modules", settings.archiveRemovedModules().maxPerRun());
            reactivationBudget = new RunBudget("reactivated projects", settings.reactivateProjects().maxPerRun());
            metadataBudget = new RunBudget("projects with updated references and tags", settings.updateProjectMetadata().maxPerRun());
            for (var reason : Reason.values()) {
                stateChanges.put(reason, new AtomicInteger());
            }
        }

        RunBudget budgetOf(Reason reason) {
            return switch (reason) {
                case REPOSITORY_ARCHIVED, REPOSITORY_GONE -> orphanedBudget;
                case MODULE_REMOVED -> removedModulesBudget;
                case REPOSITORY_ACTIVE -> reactivationBudget;
                // part of the renaming, which has its own budget
                case SUPERSEDED -> throw new IllegalArgumentException("Superseded projects are archived by renaming");
            };
        }

        int changed(Reason reason) {
            return stateChanges.get(reason).get();
        }
    }
}
