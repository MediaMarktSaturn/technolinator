package com.mediamarktsaturn.technolinator.report;

import io.quarkus.logging.Log;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/**
 * {@link CommentApi} on top of the GitHub API. Not thread-safe, meant to be used for a single reporting.
 */
public class GitHubCommentApi implements CommentApi {

    private final GHPullRequest pullRequest;
    private final Map<String, GHIssueComment> comments = new HashMap<>();

    public GitHubCommentApi(GHPullRequest pullRequest) {
        this.pullRequest = pullRequest;
    }

    @Override
    public List<Comment> findComments(Predicate<String> bodyFilter) {
        var result = new ArrayList<Comment>();
        StreamSupport.stream(pullRequest.queryComments().list().spliterator(), false)
            .filter(comment -> bodyFilter.test(comment.getBody()))
            .forEach(comment -> {
                var id = String.valueOf(comment.getId());
                comments.put(id, comment);
                result.add(new Comment(id, comment.getBody(), authorOf(comment)));
            });
        return result;
    }

    @Override
    public void create(String body) throws IOException {
        var created = pullRequest.comment(body);
        Log.infof("Created comment %s", created == null ? "" : created.getHtmlUrl());
    }

    @Override
    public void update(String id, String body) throws IOException {
        var comment = comments.get(id);
        comment.update(body);
        Log.infof("Updated comment %s", comment.getHtmlUrl());
    }

    @Override
    public void delete(String id) throws IOException {
        var comment = comments.get(id);
        comment.delete();
        Log.infof("Deleted duplicate comment %s", comment.getHtmlUrl());
    }

    private static String authorOf(GHIssueComment comment) {
        try {
            var user = comment.getUser();
            return user == null || user.getLogin() == null ? "" : user.getLogin();
        } catch (IOException e) {
            return "";
        }
    }
}
