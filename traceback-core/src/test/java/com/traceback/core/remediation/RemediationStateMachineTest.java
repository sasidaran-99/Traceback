package com.traceback.core.remediation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemediationStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED, VALIDATED",
            "CREATED, PRE_GIT_CAS_INTENT",
            "CREATED, FAILED",
            "CREATED, CANCELLED",
            "VALIDATED, PRE_GIT_CAS_INTENT",
            "VALIDATED, FAILED",
            "VALIDATED, CANCELLED",
            "PRE_GIT_CAS_INTENT, GIT_CAS_SUCCEEDED",
            "PRE_GIT_CAS_INTENT, RECOVERY_REQUIRED",
            "PRE_GIT_CAS_INTENT, FAILED",
            "GIT_CAS_SUCCEEDED, PERSISTED",
            "GIT_CAS_SUCCEEDED, RECOVERY_REQUIRED",
            "GIT_CAS_SUCCEEDED, FAILED",
            "RECOVERY_REQUIRED, PERSISTED",
            "RECOVERY_REQUIRED, FAILED"
    })
    @DisplayName("Valid transitions are permitted")
    void validTransitions(RemediationOperationStatus from, RemediationOperationStatus to) {
        assertTrue(RemediationStateMachine.canTransition(from, to));
        assertDoesNotThrow(() -> RemediationStateMachine.validateTransition(from, to));
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, GIT_CAS_SUCCEEDED",
            "CREATED, PERSISTED",
            "CREATED, RECOVERY_REQUIRED",
            "VALIDATED, PERSISTED",
            "VALIDATED, GIT_CAS_SUCCEEDED",
            "PRE_GIT_CAS_INTENT, CREATED",
            "PRE_GIT_CAS_INTENT, PERSISTED",
            "GIT_CAS_SUCCEEDED, CREATED",
            "GIT_CAS_SUCCEEDED, VALIDATED",
            "PERSISTED, CREATED",
            "PERSISTED, FAILED",
            "PERSISTED, CANCELLED",
            "FAILED, CREATED",
            "FAILED, PERSISTED",
            "CANCELLED, CREATED",
            "CANCELLED, PERSISTED"
    })
    @DisplayName("Invalid transitions are rejected")
    void invalidTransitions(RemediationOperationStatus from, RemediationOperationStatus to) {
        assertFalse(RemediationStateMachine.canTransition(from, to));
        assertThrows(RemediationInvalidStateTransitionException.class,
                () -> RemediationStateMachine.validateTransition(from, to));
    }

    @Test
    @DisplayName("Self transitions are rejected")
    void selfTransitionsRejected() {
        for (RemediationOperationStatus status : RemediationOperationStatus.values()) {
            assertFalse(RemediationStateMachine.canTransition(status, status));
            assertThrows(RemediationInvalidStateTransitionException.class,
                    () -> RemediationStateMachine.validateTransition(status, status));
        }
    }
}
