package com.mbientlab.function;

public interface Function<T, R> {
    R apply(T arg1);
}