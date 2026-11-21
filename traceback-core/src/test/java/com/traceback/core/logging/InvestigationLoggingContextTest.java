package com.traceback.core.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InvestigationLoggingContext — MDC Correlation Context Tests")
class InvestigationLoggingContextTest {

    @BeforeEach
    @AfterEach
    void cleanMdc() {
        InvestigationLoggingContext.clear();
    }

    @Test
    @DisplayName("Should set investigationId in MDC for UUID scope and clear on close")
    void testWithInvestigationIdUuidSetsAndClearsMdc() {
        UUID id = UUID.randomUUID();
        assertNull(InvestigationLoggingContext.get());
        assertNull(MDC.get(InvestigationLoggingContext.MDC_KEY_INVESTIGATION_ID));

        try (var ignored = InvestigationLoggingContext.withInvestigationId(id)) {
            assertEquals(id.toString(), InvestigationLoggingContext.get());
            assertEquals(id.toString(), MDC.get(InvestigationLoggingContext.MDC_KEY_INVESTIGATION_ID));
        }

        assertNull(InvestigationLoggingContext.get());
        assertNull(MDC.get(InvestigationLoggingContext.MDC_KEY_INVESTIGATION_ID));
    }

    @Test
    @DisplayName("Should set investigationId in MDC for String scope and clear on close")
    void testWithInvestigationIdStringSetsAndClearsMdc() {
        String idStr = UUID.randomUUID().toString();
        assertNull(InvestigationLoggingContext.get());

        try (var ignored = InvestigationLoggingContext.withInvestigationId(idStr)) {
            assertEquals(idStr, InvestigationLoggingContext.get());
            assertEquals(idStr, MDC.get(InvestigationLoggingContext.MDC_KEY_INVESTIGATION_ID));
        }

        assertNull(InvestigationLoggingContext.get());
        assertNull(MDC.get(InvestigationLoggingContext.MDC_KEY_INVESTIGATION_ID));
    }

    @Test
    @DisplayName("Should restore previous context in nested scopes")
    void testNestedScopesRestorePreviousContext() {
        UUID outerId = UUID.randomUUID();
        UUID innerId = UUID.randomUUID();

        try (var outer = InvestigationLoggingContext.withInvestigationId(outerId)) {
            assertEquals(outerId.toString(), InvestigationLoggingContext.get());

            try (var inner = InvestigationLoggingContext.withInvestigationId(innerId)) {
                assertEquals(innerId.toString(), InvestigationLoggingContext.get());
            }

            assertEquals(outerId.toString(), InvestigationLoggingContext.get());
        }

        assertNull(InvestigationLoggingContext.get());
    }

    @Test
    @DisplayName("Should safely handle null or blank investigation IDs as no-ops")
    void testNullOrBlankHandling() {
        UUID nonNullId = UUID.randomUUID();

        try (var outer = InvestigationLoggingContext.withInvestigationId(nonNullId)) {
            assertEquals(nonNullId.toString(), InvestigationLoggingContext.get());

            try (var ignored = InvestigationLoggingContext.withInvestigationId((UUID) null)) {
                assertEquals(nonNullId.toString(), InvestigationLoggingContext.get());
            }

            try (var ignored = InvestigationLoggingContext.withInvestigationId((String) null)) {
                assertEquals(nonNullId.toString(), InvestigationLoggingContext.get());
            }

            try (var ignored = InvestigationLoggingContext.withInvestigationId("   ")) {
                assertEquals(nonNullId.toString(), InvestigationLoggingContext.get());
            }

            assertEquals(nonNullId.toString(), InvestigationLoggingContext.get());
        }

        assertNull(InvestigationLoggingContext.get());
    }

    @Test
    @DisplayName("Should support explicit put and clear operations")
    void testExplicitPutAndClear() {
        UUID id = UUID.randomUUID();
        InvestigationLoggingContext.put(id);
        assertEquals(id.toString(), InvestigationLoggingContext.get());

        InvestigationLoggingContext.clear();
        assertNull(InvestigationLoggingContext.get());
    }
}
