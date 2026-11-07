package com.traceback.core.domain;

/**
 * Functional classification of an ingested event.
 */
public enum EventType {
    COMMIT,
    CI_BUILD,
    DEPLOYMENT,
    CONFIG_CHANGE,
    ERROR_BURST,
    HEALTH_DOWN,
    EXTERNAL_ALERT
}
