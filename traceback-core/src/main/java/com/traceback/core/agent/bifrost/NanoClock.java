package com.traceback.core.agent.bifrost;

/**
 * Monotonic clock abstraction providing nanoTime for deterministic deadline and timeout testing.
 */
@FunctionalInterface
public interface NanoClock {
    long nanoTime();

    NanoClock SYSTEM = System::nanoTime;
}
