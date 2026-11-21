package com.traceback.api.exception;

import com.traceback.api.dto.ApiErrorResponse;
import com.traceback.core.investigation.DeduplicationInterruptedException;
import com.traceback.core.investigation.DeduplicationWaitTimeoutException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Centralized REST exception handler providing structured, actionable error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        BindingResult result = ex.getBindingResult();
        List<ApiErrorResponse.FieldErrorDetail> fieldErrors = result.getFieldErrors().stream()
                .map(err -> new ApiErrorResponse.FieldErrorDetail(
                        err.getField(),
                        err.getRejectedValue(),
                        err.getDefaultMessage()))
                .toList();

        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Payload validation failed with " + fieldErrors.size() + " error(s)",
                request.getRequestURI(),
                fieldErrors
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        log.warn("Malformed HTTP request body: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                "Malformed JSON request body or invalid enum/timestamp value: " + ex.getMostSpecificCause().getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        log.warn("Method argument type mismatch on parameter '{}': {}", ex.getName(), ex.getMessage());
        String paramName = ex.getName() != null ? ex.getName() : "parameter";
        String message = "Invalid format for parameter '" + paramName + "': " +
                (ex.getRequiredType() != null && ex.getRequiredType().equals(java.util.UUID.class)
                        ? "expected a valid UUID string"
                        : "value could not be converted to " + (ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "required type"));

        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                message,
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(com.traceback.core.remediation.RemediationPreconditionConflictException.class)
    public ResponseEntity<ApiErrorResponse> handlePreconditionConflict(
            com.traceback.core.remediation.RemediationPreconditionConflictException ex, HttpServletRequest request) {

        log.warn("Break-glass precondition conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                "Conflict",
                sanitizeErrorMessage(ex.getMessage()),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        log.warn("Invalid argument supplied: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                sanitizeErrorMessage(ex.getMessage()),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(com.traceback.core.security.RemediationAuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleRemediationAuthentication(
            com.traceback.core.security.RemediationAuthenticationException ex, HttpServletRequest request) {

        log.warn("Authentication failed on {}: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                ex.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(com.traceback.core.security.RemediationAuthorizationException.class)
    public ResponseEntity<ApiErrorResponse> handleRemediationAuthorization(
            com.traceback.core.security.RemediationAuthorizationException ex, HttpServletRequest request) {

        log.warn("Authorization rejected on {}: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                "Forbidden",
                ex.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler({SecurityException.class, org.springframework.security.access.AccessDeniedException.class})
    public ResponseEntity<ApiErrorResponse> handleSecurityException(
            Exception ex, HttpServletRequest request) {

        log.warn("Security boundary violation on {}: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.FORBIDDEN.value(),
                "Forbidden",
                ex.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            NoSuchElementException ex, HttpServletRequest request) {

        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(DeduplicationWaitTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleDeduplicationWaitTimeout(
            DeduplicationWaitTimeoutException ex, HttpServletRequest request) {

        log.warn("Follower request timed out waiting for duplicate in-flight investigation: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.GATEWAY_TIMEOUT.value(),
                "Gateway Timeout",
                ex.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .header("Retry-After", "5")
                .body(body);
    }

    @ExceptionHandler(DeduplicationInterruptedException.class)
    public ResponseEntity<ApiErrorResponse> handleDeduplicationInterrupted(
            DeduplicationInterruptedException ex, HttpServletRequest request) {

        log.warn("Follower request thread interrupted: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                ex.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(com.traceback.core.remediation.RemediationOperationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleRemediationNotFound(
            com.traceback.core.remediation.RemediationOperationNotFoundException ex, HttpServletRequest request) {

        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler({
            com.traceback.core.remediation.RemediationTargetRefFrozenException.class,
            com.traceback.core.remediation.RemediationConcurrencyConflictException.class,
            com.traceback.core.remediation.StaleFencingTokenException.class
    })
    public ResponseEntity<ApiErrorResponse> handleRemediationConflict(
            RuntimeException ex, HttpServletRequest request) {

        log.warn("Remediation conflict on {}: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.CONFLICT.value(),
                "Conflict",
                sanitizeErrorMessage(ex.getMessage()),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(com.traceback.core.remediation.RemediationInvalidStateTransitionException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidStateTransition(
            com.traceback.core.remediation.RemediationInvalidStateTransitionException ex, HttpServletRequest request) {

        log.warn("Invalid state transition on {}: {}", request.getRequestURI(), ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                sanitizeErrorMessage(ex.getMessage()),
                request.getRequestURI(),
                Collections.emptyList()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {

        log.error("Unhandled internal server error processing request: {}", request.getRequestURI(), ex);
        ApiErrorResponse body = ApiErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected internal error occurred",
                request.getRequestURI(),
                Collections.emptyList()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    public static String sanitizeErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        if (message.isBlank()) {
            return message;
        }
        String sanitized = message;
        // 1. Quoted Windows paths (e.g. "C:\Program Files\Repo" or 'C:\foo\bar')
        sanitized = sanitized.replaceAll("[\"'][A-Za-z]:[\\\\/][^\"']+[\"']", "<redacted-path>");
        // 2. Quoted Unix paths (e.g. "/var/my app/repo")
        sanitized = sanitized.replaceAll("[\"']/(?:[^\"'/]+[\\\\/])+[^\"'/]*[\"']", "<redacted-path>");
        // 3. File URIs (e.g. file:///C:/path or file:///var/path)
        sanitized = sanitized.replaceAll("file:///(?:[A-Za-z]:[\\\\/])?[^\\s\"']+", "<redacted-path>");
        // 4. Unquoted Windows paths (e.g. C:\Program Files\TraceBack\repo or C:/repos/proj)
        sanitized = sanitized.replaceAll("\\b[A-Za-z]:(?:[\\\\/][a-zA-Z0-9_.\\- ()]+)*[\\\\/][a-zA-Z0-9_.\\-()]+", "<redacted-path>");
        // 5. Unquoted Unix paths: preceded by whitespace or boundary, never in the middle of a word like refs/heads/main
        sanitized = sanitized.replaceAll("(?<!\\S)/(?:[a-zA-Z0-9_.\\- ]+[\\\\/])+[a-zA-Z0-9_.\\-]+", "<redacted-path>");
        // 6. UNC network paths (e.g. \\server\share\repo)
        sanitized = sanitized.replaceAll("\\\\[a-zA-Z0-9_.\\-]+\\\\[a-zA-Z0-9_.\\-]+(?:\\\\[a-zA-Z0-9_.\\-]+)*", "<redacted-path>");
        // 7. Embedded credentials in URLs (e.g. https://user:pass@host/repo)
        sanitized = sanitized.replaceAll("(https?://)[^:\\s]+:[^@\\s]+@", "$1<redacted-credentials>@");
        return sanitized;
    }
}
