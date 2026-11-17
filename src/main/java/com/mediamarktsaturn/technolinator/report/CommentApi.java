package com.mediamarktsaturn.technolinator.report;

import java.io.IOException;
import java.util.List;
import java.util.function.Predicate;

/**
 * The few operations on the comments of one pull-request needed for the status comment.
 * Keeps {@link PrStatusCommentService} independent of the GitHub client.
 */
public interface CommentApi {

    /**
     * @param id          opaque identification, only to be passed back to this API
     * @param authorLogin login of the author, empty if unknown
     */
    record Comment(String id, String body, String authorLogin) {
    }

    /**
     * @param bodyFilter selects the comments to return, so that nothing needs to be looked up for all others
     * @return the matching comments, the oldest first
     */
    List<Comment> findComments(Predicate<String> bodyFilter) throws IOException;

    /**
     * @return link to the created comment
     */
    String create(String body) throws IOException;

    /**
     * @return link to the updated comment
     */
    String update(String id, String body) throws IOException;

    void delete(String id) throws IOException;
}
