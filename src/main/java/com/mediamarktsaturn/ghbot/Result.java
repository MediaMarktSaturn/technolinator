package com.mediamarktsaturn.ghbot;

import java.util.function.Function;

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
            case Success<T> s -> new Result.Success<>(successMapper.apply(s.result));
            case Failure<T> f -> new Result.Failure<U>(f.cause());
        };
    }
}
