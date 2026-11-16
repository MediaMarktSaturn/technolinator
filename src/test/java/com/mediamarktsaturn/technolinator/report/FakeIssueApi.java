package com.mediamarktsaturn.technolinator.report;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * In-memory issues of one repository.
 */
class FakeIssueApi implements IssueApi {

    static class Issue {
        int number;
        String title;
        String body;
        Instant createdAt;
        boolean open = true;
        boolean closedAsCompleted;
        List<String> assignees = new ArrayList<>();
        List<String> comments = new ArrayList<>();
    }

    final List<Issue> issues = new ArrayList<>();
    final List<String> writes = new ArrayList<>();
    boolean issuesEnabled = true;
    String codeOwners;
    IOException failNextWrite;

    @Override
    public boolean issuesEnabled() {
        return issuesEnabled;
    }

    @Override
    public List<StatusIssue> findStatusIssues() {
        return issues.stream()
            .filter(i -> StatusMarker.isPresentIn(i.body))
            .sorted(Comparator.comparing((Issue i) -> i.createdAt).reversed().thenComparing(i -> -i.number))
            .map(FakeIssueApi::toStatusIssue)
            .toList();
    }

    @Override
    public StatusIssue create(String title, String body, List<String> assignees) throws IOException {
        maybeFail();
        var issue = new Issue();
        issue.number = issues.size() + 1;
        issue.title = title;
        issue.body = body;
        issue.createdAt = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(3600L * issue.number);
        issue.assignees.addAll(assignees);
        issues.add(issue);
        writes.add("create #" + issue.number);
        return toStatusIssue(issue);
    }

    @Override
    public void updateBody(int number, String body) throws IOException {
        maybeFail();
        get(number).body = body;
        writes.add("update #" + number);
    }

    @Override
    public void comment(int number, String body) throws IOException {
        maybeFail();
        get(number).comments.add(body);
        writes.add("comment #" + number);
    }

    @Override
    public void close(int number, boolean completed) throws IOException {
        maybeFail();
        var issue = get(number);
        issue.open = false;
        issue.closedAsCompleted = completed;
        writes.add("close #" + number);
    }

    @Override
    public Optional<String> readCodeOwners() {
        return Optional.ofNullable(codeOwners);
    }

    Issue get(int number) {
        return issues.stream().filter(i -> i.number == number).findFirst().orElseThrow();
    }

    /**
     * A person closes the issue, without touching its description.
     */
    void closeByPerson(int number) {
        get(number).open = false;
    }

    long openCount() {
        return issues.stream().filter(i -> i.open).count();
    }

    private void maybeFail() throws IOException {
        if (failNextWrite != null) {
            var failure = failNextWrite;
            failNextWrite = null;
            throw failure;
        }
    }

    private static StatusIssue toStatusIssue(Issue i) {
        return new StatusIssue(i.number, i.body, i.createdAt, i.open);
    }
}
