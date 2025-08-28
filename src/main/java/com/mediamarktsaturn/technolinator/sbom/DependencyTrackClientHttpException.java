package com.mediamarktsaturn.technolinator.sbom;

public class DependencyTrackClientHttpException extends RuntimeException {
    private final int httpStatus;

    public DependencyTrackClientHttpException(int httpStatus, String message) {
        super(String.format("Dependency Track Server Http Status %d: %s", httpStatus, message));
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
