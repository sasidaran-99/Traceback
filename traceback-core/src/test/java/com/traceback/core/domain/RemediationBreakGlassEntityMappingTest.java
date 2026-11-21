package com.traceback.core.domain;

import com.traceback.core.remediation.RemediationBreakGlassDecision;
import com.traceback.core.remediation.RemediationBreakGlassStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RemediationBreakGlassEntityMappingTest {

    @Test
    @DisplayName("RemediationBreakGlassRequestEntity must be annotated with @Entity and @Table(name = 'remediation_break_glass_requests')")
    void testEntityAndTableAnnotations() {
        Class<RemediationBreakGlassRequestEntity> clazz = RemediationBreakGlassRequestEntity.class;
        assertThat(clazz.isAnnotationPresent(Entity.class)).isTrue();
        Table table = clazz.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("remediation_break_glass_requests");
    }

    @Test
    @DisplayName("RemediationBreakGlassDecision enum must support strictly KEEP_FROZEN")
    void testDecisionEnumValues() {
        RemediationBreakGlassDecision[] values = RemediationBreakGlassDecision.values();
        assertThat(values).containsExactly(RemediationBreakGlassDecision.KEEP_FROZEN);
    }

    @Test
    @DisplayName("RemediationBreakGlassStatus enum must support exactly 6 approved lifecycle statuses with terminal check")
    void testStatusEnumValues() {
        RemediationBreakGlassStatus[] values = RemediationBreakGlassStatus.values();
        assertThat(values).containsExactlyInAnyOrder(
                RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL,
                RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN,
                RemediationBreakGlassStatus.REJECTED,
                RemediationBreakGlassStatus.EXPIRED,
                RemediationBreakGlassStatus.CANCELLED,
                RemediationBreakGlassStatus.STALE_INVALIDATED
        );

        assertThat(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL.isTerminal()).isFalse();
        assertThat(RemediationBreakGlassStatus.EXECUTED_KEPT_FROZEN.isTerminal()).isTrue();
        assertThat(RemediationBreakGlassStatus.REJECTED.isTerminal()).isTrue();
        assertThat(RemediationBreakGlassStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(RemediationBreakGlassStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RemediationBreakGlassStatus.STALE_INVALIDATED.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("All 27 entity fields must map to exact V7 column names and immutability settings")
    void testFieldColumnMappings() {
        Map<String, Field> fields = Arrays.stream(RemediationBreakGlassRequestEntity.class.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, f -> f));

        // Id
        Field idField = fields.get("id");
        assertThat(idField.isAnnotationPresent(Id.class)).isTrue();
        Column idCol = idField.getAnnotation(Column.class);
        assertThat(idCol.name()).isEqualTo("id");
        assertThat(idCol.updatable()).isFalse();

        // Immutable snapshot fields
        assertImmutableColumn(fields.get("operationId"), "operation_id", false);
        assertImmutableColumn(fields.get("repositoryPath"), "repository_path", false);
        assertImmutableColumn(fields.get("targetRef"), "target_ref", false);
        assertImmutableColumn(fields.get("expectedOldSha"), "expected_old_sha", false);
        assertImmutableColumn(fields.get("proposedNewSha"), "proposed_new_sha", false);
        assertImmutableColumn(fields.get("snapshotGitSha"), "snapshot_git_sha", false);
        assertImmutableColumn(fields.get("operationFencingToken"), "operation_fencing_token", false);
        assertImmutableColumn(fields.get("requestedDecision"), "requested_decision", false);
        assertImmutableColumn(fields.get("rationale"), "rationale", false);
        assertImmutableColumn(fields.get("evidenceDigest"), "evidence_digest", true);
        assertImmutableColumn(fields.get("evidenceBundleRef"), "evidence_bundle_ref", true);
        assertImmutableColumn(fields.get("evidenceBundleVersion"), "evidence_bundle_version", true);
        assertImmutableColumn(fields.get("initiatorIssuer"), "initiator_issuer", false);
        assertImmutableColumn(fields.get("initiatorSubject"), "initiator_subject", false);
        assertImmutableColumn(fields.get("createdAt"), "created_at", false);
        assertImmutableColumn(fields.get("expiresAt"), "expires_at", false);

        // Mutable lifecycle fields
        assertMutableColumn(fields.get("status"), "status", false);
        assertMutableColumn(fields.get("approverIssuer"), "approver_issuer", true);
        assertMutableColumn(fields.get("approverSubject"), "approver_subject", true);
        assertMutableColumn(fields.get("resolvedAt"), "resolved_at", true);
        assertMutableColumn(fields.get("rejectionReason"), "rejection_reason", true);
        assertMutableColumn(fields.get("executionNotes"), "execution_notes", true);
        assertMutableColumn(fields.get("cancelledByIssuer"), "cancelled_by_issuer", true);
        assertMutableColumn(fields.get("cancelledBySubject"), "cancelled_by_subject", true);
        assertMutableColumn(fields.get("cancelledAt"), "cancelled_at", true);
        assertMutableColumn(fields.get("cancellationReason"), "cancellation_reason", true);

        // Enumerated checks
        Enumerated decisionEnum = fields.get("requestedDecision").getAnnotation(Enumerated.class);
        assertThat(decisionEnum).isNotNull();
        assertThat(decisionEnum.value()).isEqualTo(EnumType.STRING);

        Enumerated statusEnum = fields.get("status").getAnnotation(Enumerated.class);
        assertThat(statusEnum).isNotNull();
        assertThat(statusEnum.value()).isEqualTo(EnumType.STRING);
    }

    @Test
    @DisplayName("Entity getters, setters, and equals/hashCode must function properly")
    void testEntityAccessorsAndEquality() {
        UUID id = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        Instant now = Instant.now();

        RemediationBreakGlassRequestEntity e1 = new RemediationBreakGlassRequestEntity();
        e1.setId(id);
        e1.setOperationId(opId);
        e1.setRepositoryPath("C:/repos/app");
        e1.setTargetRef("refs/heads/main");
        e1.setExpectedOldSha("1111111111111111111111111111111111111111");
        e1.setProposedNewSha("2222222222222222222222222222222222222222");
        e1.setSnapshotGitSha("1111111111111111111111111111111111111111");
        e1.setOperationFencingToken(3L);
        e1.setRequestedDecision(RemediationBreakGlassDecision.KEEP_FROZEN);
        e1.setStatus(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
        e1.setRationale("Ref lock timed out; maintaining freeze");
        e1.setEvidenceDigest("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        e1.setEvidenceBundleRef("urn:traceback:evidence:123");
        e1.setEvidenceBundleVersion("v1");
        e1.setInitiatorIssuer("https://auth.traceback.internal");
        e1.setInitiatorSubject("alice-admin");
        e1.setCreatedAt(now);
        e1.setExpiresAt(now.plusSeconds(1800));

        assertThat(e1.getId()).isEqualTo(id);
        assertThat(e1.getOperationId()).isEqualTo(opId);
        assertThat(e1.getRepositoryPath()).isEqualTo("C:/repos/app");
        assertThat(e1.getTargetRef()).isEqualTo("refs/heads/main");
        assertThat(e1.getRequestedDecision()).isEqualTo(RemediationBreakGlassDecision.KEEP_FROZEN);
        assertThat(e1.getStatus()).isEqualTo(RemediationBreakGlassStatus.PENDING_SECOND_APPROVAL);
        assertThat(e1.getInitiatorIssuer()).isEqualTo("https://auth.traceback.internal");
        assertThat(e1.getInitiatorSubject()).isEqualTo("alice-admin");
        assertThat(e1.getCreatedAt()).isEqualTo(now);

        RemediationBreakGlassRequestEntity e2 = new RemediationBreakGlassRequestEntity();
        e2.setId(id);
        assertThat(e1).isEqualTo(e2);
        assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
    }

    private void assertImmutableColumn(Field field, String expectedColName, boolean nullable) {
        Column col = field.getAnnotation(Column.class);
        assertThat(col).as("Column annotation for %s", field.getName()).isNotNull();
        assertThat(col.name()).isEqualTo(expectedColName);
        assertThat(col.updatable()).as("updatable for %s", field.getName()).isFalse();
        assertThat(col.nullable()).as("nullable for %s", field.getName()).isEqualTo(nullable);
    }

    private void assertMutableColumn(Field field, String expectedColName, boolean nullable) {
        Column col = field.getAnnotation(Column.class);
        assertThat(col).as("Column annotation for %s", field.getName()).isNotNull();
        assertThat(col.name()).isEqualTo(expectedColName);
        assertThat(col.updatable()).as("updatable for %s", field.getName()).isTrue();
        assertThat(col.nullable()).as("nullable for %s", field.getName()).isEqualTo(nullable);
    }
}
