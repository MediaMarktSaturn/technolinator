package com.mediamarktsaturn.technolinator.report;

/**
 * The step of the analysis pipeline a failure occurred in.
 */
public enum FailureStage {
    CHECKOUT,
    SBOM_GENERATION,
    UPLOAD
}
