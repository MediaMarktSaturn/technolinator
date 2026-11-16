package com.mediamarktsaturn.technolinator.report;

import io.quarkus.logging.Log;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reads from the actual repository, but only logs what would be written.
 */
public class DryRunIssueApi implements IssueApi {

    private final IssueApi delegate;
    private final String repo;

    public DryRunIssueApi(IssueApi delegate, String repo) {
        this.delegate = delegate;
        this.repo = repo;
    }

    @Override
    public boolean issuesEnabled() {
        return delegate.issuesEnabled();
    }

    @Override
    public List<StatusIssue> findStatusIssues() throws IOException {
        return delegate.findStatusIssues();
    }

    @Override
    public StatusIssue create(String title, String body, List<String> assignees) {
        Log.infof("[dry-run] Would create issue '%s' in %s, assigned to %s", title, repo, assignees);
        Log.debugf("[dry-run] Description:%n%s", body);
        return new StatusIssue(0, body, Instant.now(), true);
    }

    @Override
    public void updateBody(int number, String body) {
        Log.infof("[dry-run] Would update description of issue #%s in %s", number, repo);
        Log.debugf("[dry-run] Description:%n%s", body);
    }

    @Override
    public void comment(int number, String body) {
        Log.infof("[dry-run] Would comment on issue #%s in %s: %s", number, repo, body);
    }

    @Override
    public void close(int number, boolean completed) {
        Log.infof("[dry-run] Would close issue #%s in %s as %s", number, repo, completed ? "completed" : "not planned");
    }

    @Override
    public Optional<String> readCodeOwners() {
        return delegate.readCodeOwners();
    }
}
