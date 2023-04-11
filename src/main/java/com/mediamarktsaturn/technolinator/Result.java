package com.mediamarktsaturn.technolinator;

import java.util.function.Function;

/**
 * Representation of a processing result
 */
public sealed interface Result<T> {
    record Success<T>(
        T result
    ) implements Result<T> {
    }

    record Failure<T>(
        Throwable cause
    ) implements Result<T> {
    }

    default <U> Result<U> mapSuccess(Function<T, U> successMapper) {
        return switch (this) {
            case Success<T> s -> Result.success(successMapper.apply(s.result));
            case Failure<T> f -> Result.failure(f.cause());
        };
    }

    /**
     * Create a [Result.Success] from given [result]
     */
    static <T> Result<T> success(T result) {
        return new Success<>(result);
    }

    /**
     * Create a [Result.Failure] from given [cause]
     */
    static <T> Result<T> failure(Throwable cause) {
        return new Failure<>(cause);
    }
}
