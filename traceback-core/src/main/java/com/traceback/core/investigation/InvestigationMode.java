package com.traceback.core.investigation;

/**
 * Distinguishes the investigative intent and primary telemetry source.
 */
public enum InvestigationMode {
    /**
     * Standard failure root-cause analysis triggered by runtime error logs or crash stack traces.
     */
    RUNTIME_FAILURE,

    /**
     * Working tree forensics investigating uncommitted repository changes, missing tracked files,
     * or local directory anomalies without requiring application error logs or crash traces.
     */
    WORKING_TREE_FORENSICS,

    /**
     * Repository-only commit and timeline analysis without error telemetry or working tree anomalies.
     */
    REPOSITORY_ONLY
}
