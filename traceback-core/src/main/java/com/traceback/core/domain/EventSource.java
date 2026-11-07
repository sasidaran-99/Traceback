package com.traceback.core.domain;

/**
 * Originating system or component that emitted an event.
 */
public enum EventSource {
    GITHUB,
    GITHUB_ACTIONS,
    KUBERNETES,
    SPRING_BOOT,
    MANUAL,
    EXTERNAL_SYSTEM
}
