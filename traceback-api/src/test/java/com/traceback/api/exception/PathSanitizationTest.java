package com.traceback.api.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathSanitizationTest {

    @Test
    @DisplayName("Redacts quoted Windows paths with spaces")
    void redactsQuotedWindowsPathsWithSpaces() {
        String msg = "Failed to load config from \"C:\\Program Files\\TraceBack\\config.yml\" because file is locked";
        String sanitized = GlobalExceptionHandler.sanitizeErrorMessage(msg);
        assertEquals("Failed to load config from <redacted-path> because file is locked", sanitized);
    }

    @Test
    @DisplayName("Redacts unquoted Windows paths with spaces and parentheses")
    void redactsUnquotedWindowsPathsWithSpaces() {
        String msg = "Error occurred in C:\\Program Files (x86)\\TraceBack\\repo: permission denied";
        String sanitized = GlobalExceptionHandler.sanitizeErrorMessage(msg);
        assertEquals("Error occurred in <redacted-path>: permission denied", sanitized);
    }

    @Test
    @DisplayName("Redacts forward-slash Windows paths")
    void redactsForwardSlashWindowsPaths() {
        String msg = "Physical Git ref SHA cannot be resolved in C:/repos/traceback-project/repo";
        String sanitized = GlobalExceptionHandler.sanitizeErrorMessage(msg);
        assertEquals("Physical Git ref SHA cannot be resolved in <redacted-path>", sanitized);
    }

    @Test
    @DisplayName("Redacts quoted Unix paths with spaces")
    void redactsQuotedUnixPathsWithSpaces() {
        String msg = "Directory \"/var/my app/git repo\" was not found";
        String sanitized = GlobalExceptionHandler.sanitizeErrorMessage(msg);
        assertEquals("Directory <redacted-path> was not found", sanitized);
    }

    @Test
    @DisplayName("Redacts unquoted Unix paths with spaces without swallowing following sentence words")
    void redactsUnquotedUnixPathsWithSpaces() {
        String msg = "Directory /usr/local/my app/data was missing.";
        String sanitized = GlobalExceptionHandler.sanitizeErrorMessage(msg);
        assertEquals("Directory <redacted-path> was missing.", sanitized);
    }

    @Test
    @DisplayName("Preserves Git ref names like refs/heads/main")
    void preservesGitRefNames() {
        String msg = "Target ref lock record not found for: refs/heads/main";
        String sanitized = GlobalExceptionHandler.sanitizeErrorMessage(msg);
        assertEquals("Target ref lock record not found for: refs/heads/main", sanitized);
    }

    @Test
    @DisplayName("Preserves issuer and actor URLs")
    void preservesActorUrls() {
        String msg = "Dual-custody review by https://auth.traceback.test:admin-user confirmed";
        String sanitized = GlobalExceptionHandler.sanitizeErrorMessage(msg);
        assertEquals("Dual-custody review by https://auth.traceback.test:admin-user confirmed", sanitized);
    }

    @Test
    @DisplayName("Preserves non-path prose with slashes like and/or")
    void preservesNonPathText() {
        String msg = "Requires validation of triad (digest, ref, version) and/or status";
        String sanitized = GlobalExceptionHandler.sanitizeErrorMessage(msg);
        assertEquals("Requires validation of triad (digest, ref, version) and/or status", sanitized);
    }
}
