package com.mediamarktsaturn.technolinator.report;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The few operations on the issues of one repository needed for status issues.
 * Keeps {@link StatusIssueService} independent of the GitHub client.
 */
public interface IssueApi {

    /**
     * @param body the description, may be empty
     * @param url  link to the issue, may be empty
     */
    record StatusIssue(int number, String body, Instant createdAt, boolean open, String url) {
    }

    /**
     * @return whether the repository has issues at all
     */
    boolean issuesEnabled();

    /**
     * @return all issues created by Technolinator carrying a status marker, open and closed ones, the newest first
     */
    List<StatusIssue> findStatusIssues() throws IOException;

    StatusIssue create(String title, String body, List<String> assignees) throws IOException;

    void updateBody(int number, String body) throws IOException;

    void comment(int number, String body) throws IOException;

    /**
     * @param completed whether the issue got resolved, or is closed for any other reason
     */
    void close(int number, boolean completed) throws IOException;

    /**
     * @return the content of the CODEOWNERS file of the repository
     */
    Optional<String> readCodeOwners();

    /**
     * @return whether writes are only pretended, not done
     */
    default boolean dryRun() {
        return false;
    }
}
