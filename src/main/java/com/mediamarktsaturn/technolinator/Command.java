package com.mediamarktsaturn.technolinator;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.MDC;

import java.util.Optional;

/**
 * The Command interfaces describes a deferred execution of a certain action,
 * like steps in the pipeline of a push event processing.
 */
public interface Command<T> {

    /**
     * Context information on the process of a command execution
     */
    record Metadata(
        String gitRef,
        String repoFullName,
        String traceId,
        Optional<String> commitSha
    ) {
        /**
         * Place this [Metadata] in the MDC
         */
        public void writeToMDC() {
            MDC.put("ref", gitRef);
            MDC.put("repository", repoFullName);
            MDC.put("traceId", traceId);
            commitSha.ifPresent(sha -> MDC.put("commitSha", sha));
        }


        /**
         * Unique identifier for a combination of repo, ref and commit, used for identify the same origin of an analysis
         */
        public String uniqueId() {
            return repoFullName + "#" + gitRef + "#" + commitSha.orElse("");
        }

        /**
         * Construct a [Metadata] from information present on the MDC
         */
        public static Metadata readFromMDC() {
            return new Metadata(
                orEmpty(MDC.get("ref")),
                orEmpty(MDC.get("repository")),
                orEmpty(MDC.get("traceId")),
                orEmptyOptional(MDC.get("commitSha"))
            );
        }

        private static Optional<String> orEmptyOptional(Object value) {
            if (value instanceof String s) {
                return Optional.of(s);
            } else {
                return Optional.empty();
            }
        }

        private static String orEmpty(Object value) {
            if (value == null) {
                return "";
            } else {
                return String.valueOf(value);
            }
        }
    }

    Uni<Result<T>> execute(Metadata metadata);
}
