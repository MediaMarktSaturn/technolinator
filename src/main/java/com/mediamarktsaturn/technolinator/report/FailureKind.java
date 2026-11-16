package com.mediamarktsaturn.technolinator.report;

/**
 * Whom a failed analysis is attributable to, which decides whether it is reported to the repository at all.
 */
public enum FailureKind {

    /**
     * Something the repository owners can fix: the build tool or cdxgen failed on the repository content.
     */
    REPO_ACTIONABLE,

    /**
     * Technolinator or its environment is at fault (checkout, Dependency-Track, internal errors).
     * Only logged and counted, never shown in the repository. Must never count as a recovery either.
     */
    INFRASTRUCTURE,

    /**
     * An analysis ran into a timeout. Only reported if it happens repeatedly, as a single timeout is often just load.
     */
    TIMEOUT
}
