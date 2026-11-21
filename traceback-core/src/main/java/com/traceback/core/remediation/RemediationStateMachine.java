package com.traceback.core.remediation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic state machine governing the lifecycle transitions of a remediation operation.
 */
public final class RemediationStateMachine {

    private static final Map<RemediationOperationStatus, Set<RemediationOperationStatus>> PERMITTED_TRANSITIONS;

    static {
        Map<RemediationOperationStatus, Set<RemediationOperationStatus>> map = new EnumMap<>(RemediationOperationStatus.class);

        map.put(RemediationOperationStatus.CREATED, EnumSet.of(
                RemediationOperationStatus.VALIDATED,
                RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                RemediationOperationStatus.FAILED,
                RemediationOperationStatus.CANCELLED
        ));

        map.put(RemediationOperationStatus.VALIDATED, EnumSet.of(
                RemediationOperationStatus.PRE_GIT_CAS_INTENT,
                RemediationOperationStatus.FAILED,
                RemediationOperationStatus.CANCELLED
        ));

        map.put(RemediationOperationStatus.PRE_GIT_CAS_INTENT, EnumSet.of(
                RemediationOperationStatus.GIT_CAS_SUCCEEDED,
                RemediationOperationStatus.RECOVERY_REQUIRED,
                RemediationOperationStatus.FAILED
        ));

        map.put(RemediationOperationStatus.GIT_CAS_SUCCEEDED, EnumSet.of(
                RemediationOperationStatus.PERSISTED,
                RemediationOperationStatus.RECOVERY_REQUIRED,
                RemediationOperationStatus.FAILED
        ));

        map.put(RemediationOperationStatus.RECOVERY_REQUIRED, EnumSet.of(
                RemediationOperationStatus.PERSISTED,
                RemediationOperationStatus.FAILED
        ));

        // Terminal states have no outbound transitions
        map.put(RemediationOperationStatus.PERSISTED, Collections.emptySet());
        map.put(RemediationOperationStatus.FAILED, Collections.emptySet());
        map.put(RemediationOperationStatus.CANCELLED, Collections.emptySet());

        PERMITTED_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    private RemediationStateMachine() {
    }

    /**
     * Checks whether a transition between two statuses is permissible.
     */
    public static boolean canTransition(RemediationOperationStatus from, RemediationOperationStatus to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return false; // Idempotent updates are handled separately; state transitions must be strictly non-reflexive
        }
        Set<RemediationOperationStatus> allowed = PERMITTED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Asserts that a transition is valid; throws an exception if invalid.
     */
    public static void validateTransition(RemediationOperationStatus from, RemediationOperationStatus to) {
        if (!canTransition(from, to)) {
            throw new RemediationInvalidStateTransitionException(from, to);
        }
    }
}
