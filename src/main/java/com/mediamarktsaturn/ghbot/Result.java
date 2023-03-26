package com.mediamarktsaturn.ghbot;

public sealed interface Result<T> {
    record Success<T>(
        T result
    ) implements Result<T> {
    }

    // TODO: "mapOnSuccess" or similar to not handle failure just to forward it everywhere

    record Failure<T>(
        Throwable cause
    ) implements Result<T> {
    }
}
