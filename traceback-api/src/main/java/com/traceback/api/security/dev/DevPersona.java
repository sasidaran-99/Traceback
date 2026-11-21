package com.traceback.api.security.dev;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.List;
import java.util.Locale;

/**
 * Predefined fixed development personas for development authentication.
 * Server-side fixed mapping: callers can only select from these predefined personas.
 * Custom subjects, roles, or attributes are strictly rejected.
 */
public enum DevPersona {

    ALICE_ADMIN("alice-admin", List.of("ADMIN")),
    BOB_ADMIN("bob-admin", List.of("ADMIN")),
    CHARLIE_OPERATOR("charlie-operator", List.of("OPERATOR")),
    DANA_VIEWER("dana-viewer", List.of("VIEWER"));

    private final String subject;
    private final List<String> roles;

    DevPersona(String subject, List<String> roles) {
        this.subject = subject;
        this.roles = roles;
    }

    public String getSubject() {
        return subject;
    }

    public List<String> getRoles() {
        return roles;
    }

    @JsonCreator
    public static DevPersona fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (DevPersona persona : values()) {
            if (persona.name().equalsIgnoreCase(normalized) ||
                persona.getSubject().equalsIgnoreCase(value.trim())) {
                return persona;
            }
        }
        throw new IllegalArgumentException("Unknown development persona: '" + value +
                "'. Permitted personas are: ALICE_ADMIN, BOB_ADMIN, CHARLIE_OPERATOR, DANA_VIEWER");
    }
}
