package com.mediamarktsaturn.technolinator.housekeeping;

import io.quarkus.logging.Log;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;


import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Triggers the {@link HousekeepingService} on demand: {@code POST /housekeeping}.
 * <p>
 * Without parameters the housekeeping runs for everything, just like the scheduled one. It can be restricted by the query parameters
 * {@code repository} (given as {@code owner/repo}) and {@code project} (name or UUID), each of them repeatable and/or a comma separated list,
 * see {@link HousekeepingSelection}. Then only these are looked up, instead of listing all repositories and projects, see {@link TargetedListing}:
 * {@code POST /housekeeping?repository=owner/repo,owner/other-repo&project=my-project&project=1c2f...}.
 * <p>
 * The run happens in the background, the endpoint answers right away: {@code 202} if it was started, {@code 409} if no action is enabled
 * or another run is in progress, and {@code 400} for parameters without a value or repositories without owner.
 * <p>
 * It works independent of {@code app.housekeeping.enabled}, which only switches the scheduled runs. The switches of the actions still apply.
 * <p>
 * The endpoint is <b>not protected</b>, so it must not be reachable from outside of the trusted network.
 */
@ApplicationScoped
public class HousekeepingEndpoint {

    static final String PATH = "/housekeeping";
    static final String REPOSITORY_PARAM = "repository";
    static final String PROJECT_PARAM = "project";

    private final HousekeepingService service;
    private final HousekeepingWorker worker;

    public HousekeepingEndpoint(HousekeepingService service, HousekeepingWorker worker) {
        this.service = service;
        this.worker = worker;
    }

    void register(@Observes Router router) {
        router.post(PATH).handler(this::trigger);
    }

    private void trigger(RoutingContext context) {
        var request = context.request();
        var repositories = values(context, REPOSITORY_PARAM);
        var projects = values(context, PROJECT_PARAM);
        if ((request.params().contains(REPOSITORY_PARAM) && repositories.isEmpty())
            || (request.params().contains(PROJECT_PARAM) && projects.isEmpty())) {
            respond(context, 400, "Parameters '%s' and '%s' need at least one value if given".formatted(REPOSITORY_PARAM, PROJECT_PARAM), null);
            return;
        }

        var selection = new HousekeepingSelection(repositories, projects);
        var withoutOwner = selection.repositories().stream().filter(repository -> TargetedListing.parseRepository(repository).isEmpty()).sorted().toList();
        if (!withoutOwner.isEmpty()) {
            respond(context, 400, "Repositories need to be given as 'owner/repo', got %s".formatted(withoutOwner), null);
            return;
        }

        if (!worker.enabled()) {
            respond(context, 409, "Housekeeping has no enabled action", null);
            return;
        }
        if (service.isRunning()) {
            respond(context, 409, "Housekeeping is already in progress", null);
            return;
        }

        Log.infof("Housekeeping triggered manually%s", selection.isAll() ? "" : " for " + selection);
        service.run(selection).subscribe().with(
            ignored -> {
            },
            failure -> Log.error("Manually triggered housekeeping failed", failure)
        );
        respond(context, 202, "Housekeeping started", selection);
    }

    private static Set<String> values(RoutingContext context, String param) {
        var values = new LinkedHashSet<String>();
        context.queryParam(param).stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .forEach(values::add);
        return values;
    }

    private static void respond(RoutingContext context, int status, String message, HousekeepingSelection selection) {
        var body = new JsonObject().put("message", message);
        if (selection != null) {
            body.put("repositories", new JsonArray(selection.repositories().stream().sorted().toList()));
            body.put("projects", new JsonArray(selection.projects().stream().sorted().toList()));
        }
        context.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(body.encode());
    }
}
