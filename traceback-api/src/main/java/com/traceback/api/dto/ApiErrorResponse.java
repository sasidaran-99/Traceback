package com.traceback.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Standardized error response body returned by the API on validation or operational errors.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldErrorDetail> fieldErrors
) {
    public record FieldErrorDetail(
            String field,
            Object rejectedValue,
            String message
    ) {
    }

    public static ApiErrorResponse of(int status, String error, String message, String path, List<FieldErrorDetail> fieldErrors) {
        return new ApiErrorResponse(Instant.now(), status, error, message, path, fieldErrors);
    }
}
