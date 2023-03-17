package com.mediamarktsaturn.ghbot;

public sealed interface Result<T> {
    record Success<T>(
        T result
    ) implements Result<T> {
    }

    record Failure<T>(
        Throwable cause
    ) implements Result<T> {
    }
}
