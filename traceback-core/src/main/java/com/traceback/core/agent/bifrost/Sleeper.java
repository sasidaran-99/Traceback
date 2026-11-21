package com.traceback.core.agent.bifrost;

/**
 * Sleeper abstraction for deterministic backoff delay testing.
 */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;

    Sleeper SYSTEM = Thread::sleep;
}
