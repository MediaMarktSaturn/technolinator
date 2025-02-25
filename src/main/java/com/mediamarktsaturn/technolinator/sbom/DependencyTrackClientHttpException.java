package com.mediamarktsaturn.technolinator.sbom;

public class DependencyTrackClientHttpException extends RuntimeException {
    private final int httpStatus;

    public DependencyTrackClientHttpException(int httpStatus) {
        super(String.format("Dependency Track Server Http Status %d", httpStatus));
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
