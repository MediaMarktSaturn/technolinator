package com.mediamarktsaturn.technolinator.report;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * In-memory comments of one pull-request.
 */
class FakeCommentApi implements CommentApi {

    final List<Comment> comments = new ArrayList<>();
    private int ids;

    @Override
    public List<Comment> findComments(Predicate<String> bodyFilter) {
        return comments.stream().filter(c -> bodyFilter.test(c.body())).toList();
    }

    @Override
    public String create(String body) {
        add("technolinator[bot]", body);
        return "https://github.com/fake/repo/pull/1#issuecomment-" + ids;
    }

    void add(String author, String body) {
        comments.add(new Comment(String.valueOf(++ids), body, author));
    }

    @Override
    public String update(String id, String body) {
        for (var i = 0; i < comments.size(); i++) {
            if (comments.get(i).id().equals(id)) {
                comments.set(i, new Comment(id, body, comments.get(i).authorLogin()));
            }
        }
        return "https://github.com/fake/repo/pull/1#issuecomment-" + id;
    }

    @Override
    public void delete(String id) {
        comments.removeIf(c -> c.id().equals(id));
    }

    String onlyBody() {
        assertOne();
        return comments.getFirst().body();
    }

    private void assertOne() {
        if (comments.size() != 1) {
            throw new AssertionError("expected exactly one comment, but got " + comments.size());
        }
    }
}
