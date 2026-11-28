package com.mediamarktsaturn.technolinator.report;

import io.quarkus.logging.Log;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHIssueQueryBuilder;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHIssueStateReason;
import org.kohsuke.github.GHRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link IssueApi} on top of the GitHub API, for the repository of an installation of the GitHub app.
 * Not thread-safe, meant to be used for a single reporting.
 */
public class GitHubIssueApi implements IssueApi {

    public static final String LABEL = "technolinator";
    static final String LABEL_COLOR = "5319e7";
    static final String LABEL_DESCRIPTION = "Status of the Technolinator analysis";

    /**
     * Status issues are expected to be very few, so there is no need to look further.
     */
    static final int MAX_ISSUES_SCANNED = 100;

    private static final List<String> CODEOWNERS_LOCATIONS = List.of(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS");

    private final GHRepository repo;
    private final Optional<String> botLogin;
    private final Map<Integer, GHIssue> issues = new HashMap<>();

    /**
     * @param botLogin login of the GitHub app, like {@code technolinator[bot]}. Only issues written by it are touched.
     *                 Without it, issues of any bot are looked at
     */
    public GitHubIssueApi(GHRepository repo, Optional<String> botLogin) {
        this.repo = repo;
        this.botLogin = botLogin;
    }

    @Override
    public boolean issuesEnabled() {
        return repo.hasIssues() && !repo.isArchived();
    }

    @Override
    public List<StatusIssue> findStatusIssues() throws IOException {
        var query = repo.queryIssues();
        botLogin.ifPresent(query::creator);
        query.state(GHIssueState.ALL).sort(GHIssueQueryBuilder.Sort.CREATED).direction(GHDirection.DESC);

        var result = new ArrayList<StatusIssue>();
        var scanned = 0;
        var iterator = query.list().withPageSize(MAX_ISSUES_SCANNED).iterator();
        try {
            while (scanned++ < MAX_ISSUES_SCANNED && iterator.hasNext()) {
                var issue = iterator.next();
                var body = issue.getBody() == null ? "" : issue.getBody();
                if (issue.isPullRequest() || !StatusMarker.isPresentIn(body) || !isWrittenByApp(issue)) {
                    continue;
                }
                issues.put(issue.getNumber(), issue);
                result.add(toStatusIssue(issue));
            }
        } catch (GHException e) {
            // paging fails unchecked, but callers tell the causes of failures by their exceptions
            if (e.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw e;
        }
        return result;
    }

    @Override
    public StatusIssue create(String title, String body, List<String> assignees) throws IOException {
        ensureLabel();
        var builder = repo.createIssue(title).body(body).label(LABEL);
        assignees.forEach(builder::assignee);
        var issue = builder.create();
        issues.put(issue.getNumber(), issue);
        return toStatusIssue(issue);
    }

    @Override
    public void updateBody(int number, String body) throws IOException {
        issue(number).setBody(body);
    }

    @Override
    public void comment(int number, String body) throws IOException {
        issue(number).comment(body);
    }

    @Override
    public void close(int number, boolean completed) throws IOException {
        issue(number).close(completed ? GHIssueStateReason.COMPLETED : GHIssueStateReason.NOT_PLANNED);
    }

    @Override
    public Optional<String> readCodeOwners() {
        for (var location : CODEOWNERS_LOCATIONS) {
            try (var content = repo.getFileContent(location).read()) {
                return Optional.of(new String(content.readAllBytes(), StandardCharsets.UTF_8));
            } catch (GHFileNotFoundException e) {
                // try next location
            } catch (IOException e) {
                Log.debugf(e, "Could not read %s of %s", location, repo.getFullName());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Anyone can write the marker into an issue, but only the ones of the app are to be updated and closed.
     */
    private boolean isWrittenByApp(GHIssue issue) throws IOException {
        var user = issue.getUser();
        return BotIdentity.isWrittenByApp(user == null ? null : user.getLogin(), botLogin);
    }

    private void ensureLabel() {
        try {
            repo.getLabel(LABEL);
        } catch (GHFileNotFoundException e) {
            try {
                repo.createLabel(LABEL, LABEL_COLOR, LABEL_DESCRIPTION);
            } catch (IOException createFailure) {
                // GitHub creates unknown labels with the issue itself, if the app is allowed to, or ignores them
                Log.debugf(createFailure, "Could not create label %s in %s", LABEL, repo.getFullName());
            }
        } catch (IOException e) {
            Log.debugf(e, "Could not check label %s in %s", LABEL, repo.getFullName());
        }
    }

    private GHIssue issue(int number) throws IOException {
        var issue = issues.get(number);
        if (issue == null) {
            issue = repo.getIssue(number);
            issues.put(number, issue);
        }
        return issue;
    }

    private static StatusIssue toStatusIssue(GHIssue issue) throws IOException {
        return new StatusIssue(
            issue.getNumber(),
            issue.getBody() == null ? "" : issue.getBody(),
            issue.getCreatedAt(),
            issue.getState() == GHIssueState.OPEN,
            issue.getHtmlUrl() == null ? "" : issue.getHtmlUrl().toString()
        );
    }
}
