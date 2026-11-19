package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.git.TechnolinatorConfig;
import com.mediamarktsaturn.technolinator.sbom.ConfiguredProjects;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *     <li>{@value #FOLLOW_RENAMED_REPOSITORIES}: renames the Dependency-Track projects of a repository that got renamed (and updates their references),
 *     for the cases where the rename event wasn't handled</li>
 *     <li>{@value #ARCHIVE_ORPHANED_PROJECTS}: archives the Dependency-Track projects, whose GitHub repository is archived or doesn't exist anymore</li>
 *     <li>{@value #ARCHIVE_REMOVED_MODULES}: archives the child projects of an active repository, whose module isn't described by the repository's
 *     {@code technolinator.yml} (or the repository itself) anymore</li>
 *     <li>{@value #REACTIVATE_PROJECTS}: reactivates archived Dependency-Track projects, that definitely belong to an active GitHub repository and aren't
 *     superseded by an active version, which repairs projects archived by mistake</li>
 *     <li>{@value #UPDATE_PROJECT_METADATA}: backfills the external references and tags of the projects definitely belonging to an active repository,
 *     the way the BOM upload sets them</li>
 * </ul>
 */
@ApplicationScoped
public class HousekeepingWorker {

    public static final String ARCHIVE_ORPHANED_PROJECTS = "archive-orphaned-projects";
    public static final String ARCHIVE_REMOVED_MODULES = "archive-removed-modules";
    public static final String UPDATE_PROJECT_METADATA = "update-project-metadata";
    public static final String REACTIVATE_PROJECTS = "reactivate-projects";
    public static final String FOLLOW_RENAMED_REPOSITORIES = "follow-renamed-repositories";

    private final DependencyTrackClient dtrackClient;
    private final GitHubRepositoryTopics repositoryTopics;
    private final GitHubRepositoryConfig repositoryConfig;
    private final MeterRegistry meterRegistry;
    private final Duration actionTimeout;
    private final int maxArchivedPerRun;
    private final boolean updateProjectMetadata;
    private final int maxMetadataUpdatesPerRun;
    private final boolean reactivateProjects;
    private final int maxReactivatedPerRun;
    private final boolean followRenamedRepositories;
    private final int maxRenamedPerRun;
    private final boolean archiveRemovedModules;
    private final int maxRemovedModulesPerRun;
    private final List<Action> actions;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    /**
     * Without the archiving of removed modules, which needs to read the repository configuration
     */
    public HousekeepingWorker(
        DependencyTrackClient dtrackClient,
        GitHubRepositoryTopics repositoryTopics,
        MeterRegistry meterRegistry,
        Duration actionTimeout,
        boolean archiveOrphanedProjects,
        int maxArchivedPerRun,
        boolean updateProjectMetadata,
        int maxMetadataUpdatesPerRun,
        boolean reactivateProjects,
        int maxReactivatedPerRun,
        boolean followRenamedRepositories,
        int maxRenamedPerRun
    ) {
        this(dtrackClient, repositoryTopics, null, meterRegistry, actionTimeout, archiveOrphanedProjects, maxArchivedPerRun,
            updateProjectMetadata, maxMetadataUpdatesPerRun, reactivateProjects, maxReactivatedPerRun,
            followRenamedRepositories, maxRenamedPerRun, false, 0);
    }

    @Inject
    public HousekeepingWorker(
        DependencyTrackClient dtrackClient,
        GitHubRepositoryTopics repositoryTopics,
        GitHubRepositoryConfig repositoryConfig,
        MeterRegistry meterRegistry,
        @ConfigProperty(name = "app.housekeeping.task_timeout") Duration actionTimeout,
        @ConfigProperty(name = "app.housekeeping.archive-orphaned-projects.enabled") boolean archiveOrphanedProjects,
        @ConfigProperty(name = "app.housekeeping.archive-orphaned-projects.max-per-run") int maxArchivedPerRun,
        @ConfigProperty(name = "app.housekeeping.update-project-metadata.enabled") boolean updateProjectMetadata,
        @ConfigProperty(name = "app.housekeeping.update-project-metadata.max-per-run") int maxMetadataUpdatesPerRun,
        @ConfigProperty(name = "app.housekeeping.reactivate-projects.enabled") boolean reactivateProjects,
        @ConfigProperty(name = "app.housekeeping.reactivate-projects.max-per-run") int maxReactivatedPerRun,
        @ConfigProperty(name = "app.housekeeping.follow-renamed-repositories.enabled") boolean followRenamedRepositories,
        @ConfigProperty(name = "app.housekeeping.follow-renamed-repositories.max-per-run") int maxRenamedPerRun,
        @ConfigProperty(name = "app.housekeeping.archive-removed-modules.enabled") boolean archiveRemovedModules,
        @ConfigProperty(name = "app.housekeeping.archive-removed-modules.max-per-run") int maxRemovedModulesPerRun
    ) {
        this.dtrackClient = dtrackClient;
        this.repositoryTopics = repositoryTopics;
        this.repositoryConfig = repositoryConfig;
        this.meterRegistry = meterRegistry;
        this.actionTimeout = actionTimeout;
        this.maxArchivedPerRun = maxArchivedPerRun;
        this.updateProjectMetadata = updateProjectMetadata;
        this.maxMetadataUpdatesPerRun = maxMetadataUpdatesPerRun;
        this.reactivateProjects = reactivateProjects;
        this.maxReactivatedPerRun = maxReactivatedPerRun;
        this.followRenamedRepositories = followRenamedRepositories;
        this.maxRenamedPerRun = maxRenamedPerRun;
        this.archiveRemovedModules = archiveRemovedModules;
        this.maxRemovedModulesPerRun = maxRemovedModulesPerRun;
        this.actions = List.of(
            new Action(FOLLOW_RENAMED_REPOSITORIES, () -> followRenamedRepositories, this::isRenamedRepositoryUnit, this::followRenamedRepository),
            new Action(ARCHIVE_ORPHANED_PROJECTS, () -> archiveOrphanedProjects, this::isOrphanedProjectsUnit, this::archiveOrphanedProjects),
            new Action(ARCHIVE_REMOVED_MODULES, () -> archiveRemovedModules, this::hasChildProjectsOfActiveRepository, this::archiveRemovedModules),
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
        if (followRenamedRepositories) {
            Log.infof("%s %d of %d projects of renamed repositories (%d of them archived instead, as the new name is taken already)",
                run.dryRun() ? "Would have renamed" : "Renamed", state.renamed.get(), state.renameCandidates.get(), state.renameArchived.get());
        }
        Log.infof("%s %d of %d projects, whose repository is archived or doesn't exist anymore",
            run.dryRun() ? "Would have archived" : "Archived", state.archived.get(), state.archiveCandidates.get());
        if (archiveRemovedModules) {
            Log.infof("%s %d of %d projects of modules, that aren't part of their repository's configuration anymore",
                run.dryRun() ? "Would have archived" : "Archived", state.removedModulesArchived.get(), state.removedModuleCandidates.get());
        }
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
                if (!(failure instanceof ProjectsFailedException)) {
                    HousekeepingActionLog.unitFailed(action.name, unit);
                }
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
     * are superseded and get archived instead, children first. Anything else failing is left for the next run.
     * <p>
     * No more than {@code max-per-run} projects are looked at per run, checked per unit, so the last repository's projects might exceed it.
     */
    private Uni<Void> followRenamedRepository(HousekeepingUnit unit) {
        var state = state(unit.run());
        var move = unit.moved().orElseThrow();
        var target = move.to();
        var oldName = move.from().name();
        var projects = unit.projects();

        var candidatesBefore = state.renameCandidates.getAndAdd(projects.size());
        if (candidatesBefore >= maxRenamedPerRun) {
            if (!state.renameLimitReported.getAndSet(true)) {
                Log.warnf("Limit of %d renamed projects per run reached, leaving the remaining ones for the next run", maxRenamedPerRun);
            }
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
        return Uni.combine().all().unis(
                projects.stream().map(project -> rename(unit, project, oldName, target, oldVcsUrl, oldWebsiteUrl)).toList()
            ).with(DependencyTrackClient.RenameStatus.class, statuses -> statuses)
            .chain(statuses -> {
                var superseded = new ArrayList<DependencyTrackProject>();
                var failed = 0;
                for (int i = 0; i < statuses.size(); i++) {
                    switch (statuses.get(i)) {
                        case RENAMED -> state.renamed.incrementAndGet();
                        // archived projects don't need to be superseded
                        case CONFLICT -> {
                            if (projects.get(i).active()) {
                                superseded.add(projects.get(i));
                            }
                        }
                        case FAILED -> failed++;
                        case UNCHANGED -> {
                        }
                    }
                }
                var renameFailures = failed;
                return archiveSuperseded(unit, superseded, target, state).map(archiveFailures -> renameFailures + archiveFailures);
            })
            .chain(failed -> failed == 0
                ? Uni.createFrom().voidItem()
                : Uni.createFrom().failure(new ProjectsFailedException("Could not rename %d of %d projects".formatted(failed, projects.size()))));
    }

    private Uni<DependencyTrackClient.RenameStatus> rename(HousekeepingUnit unit, DependencyTrackProject project, String oldName, GitHubRepo target,
                                                           String oldVcsUrl, String oldWebsiteUrl) {
        var newName = renamed(project.name(), oldName, target.name());
        return dtrackClient.renameProjectOfRepository(project, oldName, target.name(), oldVcsUrl, oldWebsiteUrl, target.gitUrl(), target.htmlUrl())
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

    /**
     * Archives the projects that can't be renamed, as the name is taken in their version already.
     * Children go first, as parents can't be archived with active children. Stops at the first wave having failures.
     *
     * @return the number of projects that couldn't be archived
     */
    private Uni<Integer> archiveSuperseded(HousekeepingUnit unit, List<DependencyTrackProject> superseded, GitHubRepo target, RunState state) {
        if (superseded.isEmpty()) {
            return Uni.createFrom().item(0);
        }
        Log.warnf("%d project(s) of a repository renamed to %s can't be renamed, as the new name is taken already, archiving them instead", superseded.size(), target.fullName());
        var waves = new ArrayList<>(unit.groupParentsFirst(superseded));
        Collections.reverse(waves);
        Uni<Integer> chain = Uni.createFrom().item(0);
        for (var wave : waves) {
            chain = chain.chain(failedBefore -> failedBefore > 0
                ? Uni.createFrom().item(failedBefore)
                : Uni.combine().all().unis(
                        wave.stream().map(project -> logged(FOLLOW_RENAMED_REPOSITORIES, unit, project, dtrackClient.archiveProject(project.uuid()), "change", "archive")).toList()
                    ).with(Boolean.class, results -> (int) results.stream().filter(archived -> !archived).count())
                    .invoke(failed -> state.renameArchived.addAndGet(wave.size() - failed)));
        }
        return chain;
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
            projects.forEach(project -> HousekeepingActionLog.project(ARCHIVE_ORPHANED_PROJECTS, true, unit, project));
            return Uni.createFrom().voidItem();
        }

        Log.infof("Archiving %d project(s) of %s, which %s", projects.size(), subject, reason);
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (var wave : unit.activeProjectsChildrenFirst()) {
            chain = chain.chain(() -> archiveWave(unit, wave, state));
        }
        return chain;
    }

    private Uni<Void> archiveWave(HousekeepingUnit unit, List<DependencyTrackProject> wave, RunState state) {
        return Uni.combine().all().unis(
                wave.stream().map(project -> logged(ARCHIVE_ORPHANED_PROJECTS, unit, project, dtrackClient.archiveProject(project.uuid()))).toList()
            ).with(Boolean.class, results -> (int) results.stream().filter(archived -> !archived).count())
            .invoke(failed -> state.archived.addAndGet(wave.size() - failed))
            .chain(failed -> failed == 0
                ? Uni.createFrom().voidItem()
                // parents can't be archived with active children, so the remaining waves are left for the next run
                : Uni.createFrom().failure(new ProjectsFailedException("Could not archive %d of %d projects".formatted(failed, wave.size()))));
    }

    /**
     * @return the active and archived child projects of the unit, that definitely belong to its repository, see {@link RepositoryProjectMatcher#select}
     */
    private static List<DependencyTrackProject> childProjects(HousekeepingUnit unit, GitHubRepo repository, boolean active) {
        return RepositoryProjectMatcher.select(unit.projects(), repository.name(), repository.gitUrl(), repository.htmlUrl()).stream()
            .filter(project -> project.active() == active)
            .filter(project -> project.parentUuid().isPresent())
            .toList();
    }

    /**
     * The names of the projects a repository's configuration describes, and whether the configuration enables the analysis
     */
    private record ConfiguredModules(Set<String> names, boolean enabled) {

        boolean describes(DependencyTrackProject project) {
            return names.stream().anyMatch(name -> name.equalsIgnoreCase(project.name()));
        }
    }

    private ConfiguredModules configuredModules(GitHubRepo repository) throws IOException {
        var config = repositoryConfig.config(repository);
        return new ConfiguredModules(
            ConfiguredProjects.projectNames(repository.name(), config),
            config.map(TechnolinatorConfig::enable).orElse(true)
        );
    }

    private boolean hasChildProjectsOfActiveRepository(HousekeepingUnit unit) {
        return unit.repositoryState() == GitHubRepositoryIndex.State.ACTIVE
            && unit.activeProjects().stream().anyMatch(project -> project.parentUuid().isPresent());
    }

    /**
     * Archives the child projects of an active repository, whose module doesn't exist anymore: the project isn't named like one of the modules
     * described by the {@code projects} of the repository's {@code .github/technolinator.yml}, as the analysis of the default branch names them
     * (see {@link ConfiguredProjects}). Without such configuration, the repository is the only project, so all its children are obsolete.
     * Without this, the projects created by earlier analyses of modules, that were removed from git (or from the configuration) since,
     * would stay active forever.
     * <p>
     * Only children definitely belonging to the repository are looked at ({@link RepositoryProjectMatcher#select}, same as for the metadata), the
     * parent project isn't touched. Further safeguards:
     * <ul>
     *     <li>nothing happens if the configuration can't be read (or parsed), or disables the analysis of the repository</li>
     *     <li>children with a BOM uploaded after the run started are ignored, they might be created by an analysis with a newer configuration</li>
     *     <li>a project having active descendants, that aren't obsolete themselves, is left alone, as Dependency-Track rejects archiving it</li>
     *     <li>no more than {@code max-per-run} projects are archived per run, checked per unit, so the last repository's projects might exceed it</li>
     * </ul>
     * The next BOM upload of a project reactivates it, so archiving is easily reverted. {@link #reactivateProjects} doesn't revert it.
     */
    private Uni<Void> archiveRemovedModules(HousekeepingUnit unit) {
        var run = unit.run();
        var state = state(run);
        var repository = unit.repository().orElseThrow();

        if (childProjects(unit, repository, true).isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        ConfiguredModules configured;
        try {
            configured = configuredModules(repository);
        } catch (IOException e) {
            return Uni.createFrom().failure(new IllegalStateException("Could not read the configuration of repository " + repository.fullName(), e));
        }
        if (!configured.enabled()) {
            Log.debugf("Analysis of repository %s is disabled by its configuration, leaving its projects alone", repository.fullName());
            return Uni.createFrom().voidItem();
        }

        var obsolete = new LinkedHashMap<String, DependencyTrackProject>();
        childProjects(unit, repository, true).stream()
            .filter(project -> !configured.describes(project))
            .forEach(project -> obsolete.put(project.uuid(), project));
        if (obsolete.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        var recentlyUploaded = obsolete.values().stream()
            .filter(project -> project.lastBomImport().filter(imported -> imported.isAfter(run.startedAt())).isPresent())
            .toList();
        if (!recentlyUploaded.isEmpty()) {
            Log.infof("Repository %s has projects of removed modules, but project %s got a BOM uploaded since the run started, ignoring",
                repository.fullName(), recentlyUploaded.getFirst().displayName());
            return Uni.createFrom().voidItem();
        }

        // a parent can't be archived with active children, that are no obsolete modules themselves, which cascades up the hierarchy
        var active = unit.activeProjects();
        boolean removed;
        do {
            removed = obsolete.values().removeIf(candidate -> active.stream()
                .anyMatch(project -> project.parentUuid().filter(candidate.uuid()::equals).isPresent() && !obsolete.containsKey(project.uuid())));
        } while (removed);
        if (obsolete.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        var projects = List.copyOf(obsolete.values());

        var candidatesBefore = state.removedModuleCandidates.getAndAdd(projects.size());
        if (candidatesBefore >= maxRemovedModulesPerRun) {
            if (!state.removedModulesLimitReported.getAndSet(true)) {
                Log.warnf("Limit of %d archived projects of removed modules per run reached, leaving the remaining ones for the next run", maxRemovedModulesPerRun);
            }
            return Uni.createFrom().voidItem();
        }

        if (unit.dryRun()) {
            state.removedModulesArchived.addAndGet(projects.size());
            Log.infof("Would archive %d project(s) of modules removed from repository %s: %s", projects.size(), repository.fullName(),
                projects.stream().map(DependencyTrackProject::displayName).toList());
            projects.forEach(project -> HousekeepingActionLog.project(ARCHIVE_REMOVED_MODULES, true, unit, project));
            return Uni.createFrom().voidItem();
        }

        Log.infof("Archiving %d project(s) of modules removed from repository %s", projects.size(), repository.fullName());
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (var wave : unit.groupChildrenFirst(projects)) {
            chain = chain.chain(() -> archiveRemovedModulesWave(unit, wave, state));
        }
        return chain;
    }

    private Uni<Void> archiveRemovedModulesWave(HousekeepingUnit unit, List<DependencyTrackProject> wave, RunState state) {
        return Uni.combine().all().unis(
                wave.stream().map(project -> logged(ARCHIVE_REMOVED_MODULES, unit, project, dtrackClient.archiveProject(project.uuid()))).toList()
            ).with(Boolean.class, results -> (int) results.stream().filter(archived -> !archived).count())
            .invoke(failed -> state.removedModulesArchived.addAndGet(wave.size() - failed))
            .chain(failed -> failed == 0
                ? Uni.createFrom().voidItem()
                : Uni.createFrom().failure(new ProjectsFailedException("Could not archive %d of %d projects".formatted(failed, wave.size()))));
    }

    /**
     * Writes the structured log record for the outcome of the change of the project, a failure counting as unsuccessful.
     */
    private static Uni<Boolean> logged(String action, HousekeepingUnit unit, DependencyTrackProject project, Uni<Boolean> change, String... details) {
        return change
            .onFailure().recoverWithItem(failure -> {
                unit.writeToMDC();
                Log.warnf(failure, "Housekeeping action %s failed for project %s", action, project.displayName());
                return false;
            })
            .invoke(success -> {
                unit.writeToMDC();
                HousekeepingActionLog.project(action, success ? HousekeepingActionLog.Status.DONE : HousekeepingActionLog.Status.FAILED, unit, project, details);
            });
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
        var candidates = projectsToReactivate(unit, repository, project -> false);
        if (archiveRemovedModules && candidates.stream().anyMatch(candidate -> candidate.parentUuid().isPresent())) {
            // modules removed from git stay archived, see archiveRemovedModules
            ConfiguredModules configured;
            try {
                configured = configuredModules(repository);
            } catch (IOException e) {
                return Uni.createFrom().failure(new IllegalStateException("Could not read the configuration of repository " + repository.fullName(), e));
            }
            candidates = projectsToReactivate(unit, repository, project -> project.parentUuid().isPresent() && !configured.describes(project));
        }
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
            candidates.forEach(project -> HousekeepingActionLog.project(REACTIVATE_PROJECTS, true, unit, project));
            return Uni.createFrom().voidItem();
        }

        Log.infof("Reactivating %d project(s) of active repository %s", candidates.size(), repository.fullName());
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (var wave : unit.groupParentsFirst(candidates)) {
            chain = chain.chain(() -> reactivateWave(unit, wave, state));
        }
        return chain;
    }

    private static List<DependencyTrackProject> projectsToReactivate(HousekeepingUnit unit, GitHubRepo repository, Predicate<DependencyTrackProject> excluded) {
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
        candidates.values().removeIf(excluded);
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

    private Uni<Void> reactivateWave(HousekeepingUnit unit, List<DependencyTrackProject> wave, RunState state) {
        return Uni.combine().all().unis(
                wave.stream().map(project -> logged(REACTIVATE_PROJECTS, unit, project, dtrackClient.reactivateProject(project.uuid()))).toList()
            ).with(Boolean.class, results -> (int) results.stream().filter(reactivated -> !reactivated).count())
            .invoke(failed -> state.reactivated.addAndGet(wave.size() - failed))
            .chain(failed -> failed == 0
                ? Uni.createFrom().voidItem()
                // children are better left archived than active below an archived parent
                : Uni.createFrom().failure(new ProjectsFailedException("Could not reactivate %d of %d projects".formatted(failed, wave.size()))));
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
        final AtomicInteger removedModuleCandidates = new AtomicInteger();
        // in dry-run mode: would have been archived
        final AtomicInteger removedModulesArchived = new AtomicInteger();
        final AtomicBoolean removedModulesLimitReported = new AtomicBoolean();
        final AtomicInteger renameCandidates = new AtomicInteger();
        // in dry-run mode: would have been renamed
        final AtomicInteger renamed = new AtomicInteger();
        final AtomicInteger renameArchived = new AtomicInteger();
        final AtomicBoolean renameLimitReported = new AtomicBoolean();
    }
}
