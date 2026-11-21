package com.traceback.core.logging;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Utility for managing investigation correlation context in SLF4J MDC.
 * Ensures strict thread-safety, scope cleanup via AutoCloseable, and prevents thread-pool context leaks.
 */
public final class InvestigationLoggingContext {

    public static final String MDC_KEY_INVESTIGATION_ID = "investigationId";

    private InvestigationLoggingContext() {
        // Utility class
    }

    /**
     * Sets the investigation ID in the SLF4J MDC for the duration of the returned scope.
     * When closed, restores the previous value (or clears the key if there was none).
     *
     * @param investigationId the investigation ID to set, or null to do nothing
     * @return an AutoCloseable scope that cleans up MDC on close
     */
    public static Scope withInvestigationId(UUID investigationId) {
        if (investigationId == null) {
            return () -> {};
        }
        return withInvestigationId(investigationId.toString());
    }

    /**
     * Sets the investigation ID string in the SLF4J MDC for the duration of the returned scope.
     *
     * @param investigationIdStr the investigation ID string
     * @return an AutoCloseable scope that cleans up MDC on close
     */
    public static Scope withInvestigationId(String investigationIdStr) {
        if (investigationIdStr == null || investigationIdStr.isBlank()) {
            return () -> {};
        }
        String previous = MDC.get(MDC_KEY_INVESTIGATION_ID);
        MDC.put(MDC_KEY_INVESTIGATION_ID, investigationIdStr);
        return () -> {
            if (previous != null) {
                MDC.put(MDC_KEY_INVESTIGATION_ID, previous);
            } else {
                MDC.remove(MDC_KEY_INVESTIGATION_ID);
            }
        };
    }

    /**
     * Directly puts the investigation ID in MDC if non-null.
     */
    public static void put(UUID investigationId) {
        if (investigationId != null) {
            MDC.put(MDC_KEY_INVESTIGATION_ID, investigationId.toString());
        }
    }

    /**
     * Directly removes the investigation ID from MDC.
     */
    public static void clear() {
        MDC.remove(MDC_KEY_INVESTIGATION_ID);
    }

    /**
     * Retrieves the current investigation ID from MDC, if present.
     */
    public static String get() {
        return MDC.get(MDC_KEY_INVESTIGATION_ID);
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
