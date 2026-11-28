package com.mediamarktsaturn.technolinator.handler;

import com.mediamarktsaturn.technolinator.Result;
import com.mediamarktsaturn.technolinator.sbom.Project;
import com.mediamarktsaturn.technolinator.sbom.VulnerabilitySummary;

import java.util.Optional;

/**
 * What the analysis of a pull-request yielded.
 *
 * @param project         the Dependency-Track project(s) the SBOM(s) ended up in, which are none for pull-requests
 * @param vulnerabilities the number of vulnerabilities per severity, as the analyzer publishes them. A failure if they can't be told,
 *                        e.g. because the SBOM creation or the analyzer failed. Empty if they were not looked at, because the commit status doesn't ask for them
 */
public record AnalysisOutcome(
    Result<Project> project,
    Optional<Result<VulnerabilitySummary>> vulnerabilities
) {

    public static AnalysisOutcome withoutVulnerabilities(Result<Project> project) {
        return new AnalysisOutcome(project, Optional.empty());
    }
}
