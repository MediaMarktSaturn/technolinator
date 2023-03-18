package com.mediamarktsaturn.ghbot;

import org.jboss.logging.MDC;

import io.smallrye.mutiny.Uni;

public interface Command<T> {

    record Metadata(
        String gitRef,
        String repoFullName,
        String traceId,
        String commitSha
    ) {
        public void toMDC() {
            MDC.put("ref", gitRef);
            MDC.put("repository", repoFullName);
            MDC.put("traceId", traceId);
            MDC.put("commitSha", commitSha);
        }

        public static Metadata fromMDC() {
            return new Metadata(
                orEmpty(MDC.get("ref")),
                orEmpty(MDC.get("repository")),
                orEmpty(MDC.get("traceId")),
                orEmpty(MDC.get("commitSha"))
            );
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
