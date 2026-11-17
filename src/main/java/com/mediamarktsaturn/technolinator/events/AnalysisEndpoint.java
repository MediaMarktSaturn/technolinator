package com.mediamarktsaturn.technolinator.events;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.regex.Pattern;

/**
 * Triggers the analysis of the default branch of a repository on demand: {@code POST /analyze?repository=owner/repo}.
 * <p>
 * The analysis behaves exactly like the one of a push to the default branch, see {@link AnalysisTrigger}.
 * It runs in the background, the endpoint answers as soon as it's started:
 * <ul>
 *     <li>{@code 202} if the analysis was started</li>
 *     <li>{@code 400} if the parameter {@code repository} is missing, given more than once, or not given as {@code owner/repo}</li>
 *     <li>{@code 404} if the repository, its default branch, or an installation of the GitHub app having access to it don't exist</li>
 *     <li>{@code 409} if the repository is excluded by {@code app.enabled_repos}, disabled by its configuration file, archived,
 *     or an analysis of it is in progress already</li>
 *     <li>{@code 422} if the configuration file of the repository can't be read</li>
 *     <li>{@code 502} if GitHub couldn't be asked about the repository</li>
 * </ul>
 * The endpoint is <b>not protected</b>, so it must not be reachable from outside of the trusted network.
 */
@ApplicationScoped
public class AnalysisEndpoint {

    static final String PATH = "/analyze";
    static final String REPOSITORY_PARAM = "repository";

    // names of GitHub accounts and repositories, as these end up in request paths and logs
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private final AnalysisTrigger trigger;

    public AnalysisEndpoint(AnalysisTrigger trigger) {
        this.trigger = trigger;
    }

    void register(@Observes Router router) {
        // looking up the repository blocks, triggers of different repositories don't need to wait for each other
        router.post(PATH).blockingHandler(this::trigger, false);
    }

    private void trigger(RoutingContext context) {
        var values = context.queryParam(REPOSITORY_PARAM);
        if (values.size() != 1) {
            respond(context, 400, "Parameter '%s' is required exactly once".formatted(REPOSITORY_PARAM), null);
            return;
        }
        var parts = values.getFirst().trim().split("/", -1);
        if (parts.length != 2 || !isValidName(parts[0].trim()) || !isValidName(parts[1].trim())) {
            respond(context, 400, "Parameter '%s' needs to be given as 'owner/repo'".formatted(REPOSITORY_PARAM), null);
            return;
        }

        switch (trigger.trigger(parts[0].trim(), parts[1].trim())) {
            case AnalysisTrigger.Outcome.Started started -> respond(context, 202, "Analysis started", new JsonObject()
                .put("repository", started.repository())
                .put("branch", started.branch())
                .put("commit", started.commitSha())
                .put("traceId", started.traceId()));
            case AnalysisTrigger.Outcome.Rejected rejected -> respond(context, switch (rejected.reason()) {
                case NOT_FOUND -> 404;
                case NOT_ELIGIBLE, IN_PROGRESS -> 409;
                case INVALID_CONFIG -> 422;
                case GITHUB_FAILURE -> 502;
            }, rejected.message(), null);
        }
    }

    private static boolean isValidName(String name) {
        return NAME.matcher(name).matches() && !name.chars().allMatch(c -> c == '.');
    }

    private static void respond(RoutingContext context, int status, String message, JsonObject details) {
        var body = new JsonObject().put("message", message);
        if (details != null) {
            body.mergeIn(details);
        }
        context.response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(body.encode());
    }
}
