package com.traceback.core.domain;

/**
 * Status lifecycle of a candidate root-cause hypothesis.
 */
public enum HypothesisStatus {
    PROPOSED,
    INVESTIGATING,
    CONFIRMED,
    REJECTED
}
