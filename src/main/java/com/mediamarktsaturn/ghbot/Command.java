package com.mediamarktsaturn.ghbot;

import java.util.Optional;

import org.jboss.logging.MDC;

import io.smallrye.mutiny.Uni;

public interface Command<T> {

    record Metadata(
        String gitRef,
        String repoFullName,
        String traceId,
        Optional<String> commitSha
    ) {
        public void writeToMDC() {
            MDC.put("ref", gitRef);
            MDC.put("repository", repoFullName);
            MDC.put("traceId", traceId);
            commitSha.ifPresent(sha -> MDC.put("commitSha", commitSha));
        }

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
