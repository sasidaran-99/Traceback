package com.traceback.core.log;

import java.util.Objects;

/**
 * Normalized fingerprint of an application runtime failure.
 */
public record ErrorSignature(
        String exceptionType,
        String messagePattern,
        String sourceLocation
) {
    public static ErrorSignature of(String exceptionType, String message, String sourceLocation) {
        String normalizedMsg = normalizeMessage(message);
        return new ErrorSignature(
                exceptionType != null ? exceptionType : "UnknownError",
                normalizedMsg,
                sourceLocation != null ? sourceLocation : "UnknownLocation"
        );
    }

    private static String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "<no-message>";
        }
        // Normalize dynamic numeric values, dates, and memory addresses
        return message.replaceAll("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\b", "<timestamp>")
                .replaceAll("0x[0-9a-fA-F]+", "<addr>")
                .replaceAll("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b", "<uuid>")
                .trim();
    }

    public String toKey() {
        return exceptionType + "::" + sourceLocation + "::" + messagePattern;
    }
}
