package com.traceback.core.remediation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProposeBreakGlassCommandTest {

    private static final UUID OP_ID = UUID.randomUUID();
    private static final String VALID_HEX_64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("All evidence fields null results in all three null")
    void allEvidenceFieldsNull() {
        ProposeBreakGlassCommand cmd = new ProposeBreakGlassCommand(OP_ID, "Rationale text", null, null, null);
        assertNull(cmd.evidenceDigest());
        assertNull(cmd.evidenceBundleRef());
        assertNull(cmd.evidenceBundleVersion());
    }

    @Test
    @DisplayName("All evidence fields empty/blank results in all three null")
    void allEvidenceFieldsBlank() {
        ProposeBreakGlassCommand cmd = new ProposeBreakGlassCommand(OP_ID, "Rationale text", "   ", "", "  ");
        assertNull(cmd.evidenceDigest());
        assertNull(cmd.evidenceBundleRef());
        assertNull(cmd.evidenceBundleVersion());
    }

    @Test
    @DisplayName("All evidence fields populated is accepted and digest is lowercased with Locale.ROOT")
    void allEvidenceFieldsPopulated() {
        String upperHex = VALID_HEX_64.toUpperCase(Locale.ROOT);
        ProposeBreakGlassCommand cmd = new ProposeBreakGlassCommand(
                OP_ID,
                "Rationale text",
                "  " + upperHex + "  ",
                "  bundle-ref-1  ",
                "  v1.0.0  "
        );
        assertEquals(VALID_HEX_64.toLowerCase(Locale.ROOT), cmd.evidenceDigest());
        assertEquals("bundle-ref-1", cmd.evidenceBundleRef());
        assertEquals("v1.0.0", cmd.evidenceBundleVersion());
    }

    @Test
    @DisplayName("Partial evidence combinations are rejected with IllegalArgumentException")
    void partialEvidenceCombinationsRejected() {
        // Digest only
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", VALID_HEX_64, null, null));

        // Ref only
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", null, "bundle-ref", null));

        // Version only
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", null, null, "v1.0"));

        // Digest + Ref (missing Version)
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", VALID_HEX_64, "bundle-ref", null));

        // Digest + Version (missing Ref)
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", VALID_HEX_64, null, "v1.0"));

        // Ref + Version (missing Digest)
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", null, "bundle-ref", "v1.0"));

        // Blank digest with populated ref and version
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", "   ", "bundle-ref", "v1.0"));
    }

    @Test
    @DisplayName("Invalid hex digest format is rejected")
    void invalidDigestFormatRejected() {
        // Less than 64 chars
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", "abcdef", "bundle-ref", "v1.0"));

        // Non-hex chars
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", "z".repeat(64), "bundle-ref", "v1.0"));
    }

    @Test
    @DisplayName("Field length constraints are enforced on ref and version")
    void lengthConstraintsEnforced() {
        // Ref > 255 chars
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", VALID_HEX_64, "a".repeat(256), "v1.0"));

        // Version > 64 chars
        assertThrows(IllegalArgumentException.class, () ->
                new ProposeBreakGlassCommand(OP_ID, "Rationale", VALID_HEX_64, "bundle-ref", "v".repeat(65)));
    }
}
