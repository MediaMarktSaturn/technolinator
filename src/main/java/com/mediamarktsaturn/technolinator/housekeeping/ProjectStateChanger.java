package com.mediamarktsaturn.technolinator.housekeeping;

import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.Change;
import com.mediamarktsaturn.technolinator.housekeeping.ProjectStatePlanner.Reason;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackClient;
import com.mediamarktsaturn.technolinator.sbom.DependencyTrackProject;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Archives and reactivates Dependency-Track projects of a {@link HousekeepingUnit}, as planned by the {@link ProjectStatePlanner} or by an action.
 * The only place housekeeping changes the state of projects, so all changes get the same safeguards:
 * <ol>
 *     <li><b>verification</b>: the plan is based on the listing taken at the start of the run, which might be hours old. So right before changing
 *     anything, the projects are fetched again: projects archived or reactivated meanwhile (or deleted) are left out, and reactivations are left
 *     out if another version of the same name got active meanwhile (an upload archives the other versions). If any of the projects got a BOM
 *     uploaded since the run started, the whole unit is left alone, as it's being analyzed and the snapshot isn't to be trusted anymore.</li>
 *     <li><b>order</b>: archiving goes children first, as Dependency-Track rejects archiving a parent with active children, and reactivating
 *     goes parents first, as children shouldn't be active below an archived parent. A failure stops the remaining projects of the same kind,
 *     they are left for the next run.</li>
 *     <li><b>bounded concurrency</b>: no more than {@value #CONCURRENCY} requests per unit at a time</li>
 *     <li><b>logging</b>: one structured {@link HousekeepingActionLog} line per project changed, or failed, telling the {@code change} and {@code reason}</li>
 * </ol>
 * In dry-run mode, nothing is fetched or changed, but the snapshot is checked for BOM uploads since the run started, and the changes are logged.
 * Limits ({@link RunBudget}) are up to the caller.
 */
final class ProjectStateChanger {

    static final int CONCURRENCY = 4;

    private final DependencyTrackClient dtrackClient;

    ProjectStateChanger(DependencyTrackClient dtrackClient) {
        this.dtrackClient = dtrackClient;
    }

    /**
     * @param skipped The unit was left alone, as a project got a BOM uploaded since the run started, nothing was changed
     * @param changed Number of projects changed (or that would have been changed in dry-run mode)
     * @param failed  Number of projects whose state couldn't be changed
     */
    record Outcome(boolean skipped, int changed, int failed) {

        static final Outcome NOTHING = new Outcome(false, 0, 0);

        static Outcome skippedUnit() {
            return new Outcome(true, 0, 0);
        }

        Outcome plus(Outcome other) {
            return new Outcome(skipped || other.skipped, changed + other.changed, failed + other.failed);
        }
    }

    /**
     * Applies the changes, which must belong to the unit. Fails only if the current state of the projects can't be fetched, then nothing is changed.
     *
     * @param action   Name of the action, for the structured log
     * @param counters Counts the projects changed per reason, while it happens, so it's accurate even if the caller gives up (e.g. on a timeout)
     */
    Uni<Outcome> apply(String action, HousekeepingUnit unit, List<Change> changes, Map<Reason, AtomicInteger> counters) {
        if (changes.isEmpty()) {
            return Uni.createFrom().item(Outcome.NOTHING);
        }
        if (unit.dryRun()) {
            return Uni.createFrom().item(() -> dryRun(action, unit, changes, counters));
        }
        return verify(unit, changes)
            .chain(verified -> {
                unit.writeToMDC();
                if (verified.uploadedSinceStart().isPresent()) {
                    logUploadedSinceStart(unit, verified.uploadedSinceStart().get());
                    return Uni.createFrom().item(Outcome.skippedUnit());
                }
                if (verified.changes().size() < changes.size()) {
                    Log.infof("%d of %d planned project state changes of %s aren't needed anymore, the projects changed meanwhile",
                        changes.size() - verified.changes().size(), changes.size(), unit.subject());
                }
                return execute(action, unit, verified.changes(), counters);
            });
    }

    private static Outcome dryRun(String action, HousekeepingUnit unit, List<Change> changes, Map<Reason, AtomicInteger> counters) {
        unit.writeToMDC();
        var uploaded = changes.stream().map(Change::project).filter(project -> uploadedSince(project, unit.run().startedAt())).findFirst();
        if (uploaded.isPresent()) {
            logUploadedSinceStart(unit, uploaded.get());
            return Outcome.skippedUnit();
        }
        byReason(changes).forEach((reason, ofReason) -> {
            Log.infof("Would %s %d project(s) of %s, as %s: %s", reason.archive ? "archive" : "reactivate", ofReason.size(), unit.subject(), reason.description,
                ofReason.stream().map(change -> change.project().displayName()).toList());
            ofReason.forEach(change -> HousekeepingActionLog.project(action, HousekeepingActionLog.Status.DRY_RUN, unit, change.project(), details(change)));
            counters.get(reason).addAndGet(ofReason.size());
        });
        return new Outcome(false, changes.size(), 0);
    }

    private static void logUploadedSinceStart(HousekeepingUnit unit, DependencyTrackProject project) {
        Log.infof("Leaving %s alone for this run, project %s got a BOM uploaded since the run started", unit.subject(), project.displayName());
    }

    private static boolean uploadedSince(DependencyTrackProject project, Instant startedAt) {
        return project.lastBomImport().filter(imported -> imported.isAfter(startedAt)).isPresent();
    }

    /**
     * @param changes            The changes still needed
     * @param uploadedSinceStart A project involved, that got a BOM uploaded since the run started, which makes the changes void
     */
    private record Verification(List<Change> changes, Optional<DependencyTrackProject> uploadedSinceStart) {
    }

    private Uni<Verification> verify(HousekeepingUnit unit, List<Change> changes) {
        var archives = changes.stream().filter(Change::archive).toList();
        var reactivations = changes.stream().filter(change -> !change.archive()).toList();
        var names = reactivations.stream().map(change -> change.project().name()).distinct().toList();

        Uni<Map<String, Optional<DependencyTrackProject>>> current = each(archives, change -> change.project().uuid(),
            change -> dtrackClient.getProject(change.project().uuid()));
        // all versions of a name, as one of them might have got active meanwhile
        Uni<Map<String, List<DependencyTrackProject>>> versions = each(names, Function.identity(),
            name -> dtrackClient.searchProjects(name).map(found -> found.stream().filter(project -> name.equals(project.name())).toList()));

        return Uni.combine().all().unis(current, versions).asTuple()
            .map(fetched -> evaluate(unit, archives, reactivations, fetched.getItem1(), fetched.getItem2()));
    }

    private static Verification evaluate(
        HousekeepingUnit unit,
        List<Change> archives,
        List<Change> reactivations,
        Map<String, Optional<DependencyTrackProject>> current,
        Map<String, List<DependencyTrackProject>> versions
    ) {
        var uploaded = Stream.concat(current.values().stream().flatMap(Optional::stream), versions.values().stream().flatMap(List::stream))
            .filter(project -> uploadedSince(project, unit.run().startedAt()))
            .findFirst();
        if (uploaded.isPresent()) {
            return new Verification(List.of(), uploaded);
        }

        var remaining = new ArrayList<Change>();
        for (var change : archives) {
            // deleted or archived meanwhile, either is fine
            if (current.getOrDefault(change.project().uuid(), Optional.empty()).filter(DependencyTrackProject::active).isPresent()) {
                remaining.add(change);
            }
        }

        var stayingArchived = new HashSet<String>();
        var remainingReactivations = new ArrayList<Change>();
        for (var change : reactivations) {
            var ofName = versions.getOrDefault(change.project().name(), List.of());
            var self = ofName.stream().filter(project -> project.uuid().equals(change.project().uuid())).findFirst();
            if (self.isEmpty()) {
                stayingArchived.add(change.project().uuid());
            } else if (self.get().active()) {
                // reactivated meanwhile, its children can follow
                continue;
            } else if (ofName.stream().anyMatch(DependencyTrackProject::active)) {
                unit.writeToMDC();
                Log.infof("Not reactivating project %s, another version of it got active meanwhile", change.project().displayName());
                stayingArchived.add(change.project().uuid());
            } else {
                remainingReactivations.add(change);
            }
        }
        // children of parents staying archived stay archived as well, which cascades down the hierarchy
        boolean removed;
        do {
            removed = false;
            for (var iterator = remainingReactivations.iterator(); iterator.hasNext(); ) {
                var change = iterator.next();
                if (change.project().parentUuid().filter(stayingArchived::contains).isPresent()) {
                    iterator.remove();
                    stayingArchived.add(change.project().uuid());
                    removed = true;
                }
            }
        } while (removed);

        remaining.addAll(remainingReactivations);
        return new Verification(List.copyOf(remaining), Optional.empty());
    }

    private Uni<Outcome> execute(String action, HousekeepingUnit unit, List<Change> changes, Map<Reason, AtomicInteger> counters) {
        byReason(changes).forEach((reason, ofReason) ->
            Log.infof("%s %d project(s) of %s, as %s", reason.archive ? "Archiving" : "Reactivating", ofReason.size(), unit.subject(), reason.description));

        var byUuid = new LinkedHashMap<String, Change>();
        changes.forEach(change -> byUuid.put(change.project().uuid(), change));
        var archives = changes.stream().filter(Change::archive).map(Change::project).toList();
        var reactivations = changes.stream().filter(change -> !change.archive()).map(Change::project).toList();

        // they're independent of each other: reactivated projects are never children of archived ones, see ProjectStatePlanner
        return inWaves(action, unit, unit.groupChildrenFirst(archives), byUuid, counters)
            .chain(archived -> inWaves(action, unit, unit.groupParentsFirst(reactivations), byUuid, counters).map(archived::plus));
    }

    /**
     * Changes the waves one after the other, stopping at the first one having failures
     */
    private Uni<Outcome> inWaves(String action, HousekeepingUnit unit, List<List<DependencyTrackProject>> waves, Map<String, Change> byUuid,
                                 Map<Reason, AtomicInteger> counters) {
        Uni<Outcome> chain = Uni.createFrom().item(Outcome.NOTHING);
        for (var wave : waves) {
            chain = chain.chain(before -> before.failed() > 0
                ? Uni.createFrom().item(before)
                : Multi.createFrom().iterable(wave)
                .onItem().transformToUni(project -> change(action, unit, byUuid.get(project.uuid()), counters))
                .merge(CONCURRENCY)
                .collect().asList()
                .map(results -> new Outcome(false, (int) results.stream().filter(changed -> changed).count(), (int) results.stream().filter(changed -> !changed).count()))
                .map(before::plus));
        }
        return chain;
    }

    private Uni<Boolean> change(String action, HousekeepingUnit unit, Change change, Map<Reason, AtomicInteger> counters) {
        var project = change.project();
        return Uni.createFrom().deferred(() -> change.archive() ? dtrackClient.archiveProject(project.uuid()) : dtrackClient.reactivateProject(project.uuid()))
            .onFailure().recoverWithItem(failure -> {
                unit.writeToMDC();
                Log.warnf(failure, "Housekeeping action %s failed for project %s", action, project.displayName());
                return false;
            })
            .invoke(changed -> {
                unit.writeToMDC();
                if (changed) {
                    counters.get(change.reason()).incrementAndGet();
                }
                HousekeepingActionLog.project(action, changed ? HousekeepingActionLog.Status.DONE : HousekeepingActionLog.Status.FAILED, unit, project, details(change));
            });
    }

    private static String[] details(Change change) {
        return new String[]{"change", change.archive() ? "archive" : "reactivate", "reason", change.reason().logValue()};
    }

    private static Map<Reason, List<Change>> byReason(Collection<Change> changes) {
        var byReason = new LinkedHashMap<Reason, List<Change>>();
        changes.forEach(change -> byReason.computeIfAbsent(change.reason(), reason -> new ArrayList<>()).add(change));
        return byReason;
    }

    /**
     * Looks up all [items] with bounded concurrency. Fails if any lookup fails.
     */
    private static <T, V> Uni<Map<String, V>> each(Collection<T> items, Function<T, String> key, Function<T, Uni<V>> lookup) {
        return Multi.createFrom().iterable(items)
            .onItem().transformToUni(item -> lookup.apply(item).map(value -> Map.entry(key.apply(item), value)))
            .merge(CONCURRENCY)
            .collect().asMap(Map.Entry::getKey, Map.Entry::getValue);
    }
}
