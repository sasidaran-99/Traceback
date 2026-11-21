package com.traceback.core.validation;

import com.traceback.core.evidence.EvidenceItem;
import com.traceback.core.evidence.EvidenceRegistry;
import com.traceback.core.evidence.EvidenceType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Concrete, deterministic implementation of ClaimValidator.
 * Ensures that all claims are backed by verifiable, un-tampered, and logically relevant evidence.
 */
@Component
public class DeterministicClaimValidator implements ClaimValidator {

    @Override
    public InvestigationClaim validateClaim(InvestigationClaim claim, UUID activeInvestigationId, EvidenceRegistry registry) {
        if (claim == null) {
            throw new IllegalArgumentException("Cannot validate null claim");
        }

        UUID targetInvestigationId = activeInvestigationId != null ? activeInvestigationId : claim.investigationId();

        List<String> supportingIds = claim.supportingEvidenceIds();

        // 1. Check for missing evidence IDs
        if (supportingIds == null || supportingIds.isEmpty()) {
            return claim.withValidation(
                    ClaimValidationStatus.VALIDATION_ERROR,
                    "Validation failed: Claim cites zero supporting evidence IDs."
            );
        }

        // 2 & 3. Retrieve and inspect all cited supporting evidence items
        List<EvidenceItem> supportingItems = new ArrayList<>();
        for (String evdId : supportingIds) {
            if (evdId == null || evdId.isBlank()) {
                return claim.withValidation(
                        ClaimValidationStatus.VALIDATION_ERROR,
                        "Validation failed: Claim contains blank or null evidence ID."
                );
            }

            Optional<EvidenceItem> itemOpt = targetInvestigationId != null
                    ? registry.findItemById(evdId, targetInvestigationId)
                    : registry.findItemById(evdId);

            if (itemOpt.isEmpty()) {
                // If not found in target scope, check if it belongs to another investigation
                Optional<EvidenceItem> foreignOpt = registry.findItemById(evdId);
                if (foreignOpt.isPresent()) {
                    Object foreignInv = foreignOpt.get().metadata().get("investigationId");
                    if (foreignInv != null && targetInvestigationId != null &&
                            !foreignInv.toString().equalsIgnoreCase(targetInvestigationId.toString()) &&
                            !"GLOBAL".equalsIgnoreCase(foreignInv.toString())) {
                        return claim.withValidation(
                                ClaimValidationStatus.VALIDATION_ERROR,
                                "Validation failed: Evidence ID '" + evdId + "' belongs to a different investigation (" +
                                        foreignInv + ") rather than active investigation (" + targetInvestigationId + ")."
                        );
                    }
                }
                return claim.withValidation(
                        ClaimValidationStatus.VALIDATION_ERROR,
                        "Validation failed: Supporting evidence ID '" + evdId + "' was not found in the active evidence registry."
                );
            }

            EvidenceItem item = itemOpt.get();

            // 1. Check multi-tenant / investigation boundary isolation (authoritative ownership)
            Object foreignInv = item.metadata().get("investigationId");
            if (foreignInv != null && targetInvestigationId != null) {
                String foreignStr = foreignInv.toString();
                if (!foreignStr.equalsIgnoreCase(targetInvestigationId.toString()) && !"GLOBAL".equalsIgnoreCase(foreignStr)) {
                    return claim.withValidation(
                            ClaimValidationStatus.VALIDATION_ERROR,
                            "Validation failed: Evidence ID '" + evdId + "' belongs to a different investigation (" +
                                    foreignStr + ") rather than active investigation (" + targetInvestigationId + ")."
                    );
                }
            }

            // 2. Check collection provenance: evidence must be genuinely collected for this investigation,
            // not copied/leaked from another investigation without independent regeneration
            UUID origInv = item.originatingInvestigationId();
            if (origInv != null && targetInvestigationId != null && !targetInvestigationId.equals(origInv)
                    && !"GLOBAL".equalsIgnoreCase(String.valueOf(item.metadata().get("originatingInvestigationId")))) {
                return claim.withValidation(
                        ClaimValidationStatus.VALIDATION_ERROR,
                        "Validation failed: Evidence ID '" + evdId + "' originated from a different investigation (" +
                                origInv + ") rather than active investigation (" + targetInvestigationId + ")."
                );
            }

            // Verify cryptographic SHA-256 integrity hash
            if (!verifyEvidenceIntegrity(item)) {
                return claim.withValidation(
                        ClaimValidationStatus.VALIDATION_ERROR,
                        "Validation failed: Evidence ID '" + evdId + "' failed cryptographic SHA-256 integrity checksum (tampered or corrupted content)."
                );
            }

            supportingItems.add(item);
        }

        // 4. Inspect contradictory evidence first: does counter-evidence directly refute this claim?
        List<String> contradictingIds = claim.contradictingEvidenceIds();
        List<EvidenceItem> contradictingItems = new ArrayList<>();
        if (contradictingIds != null && !contradictingIds.isEmpty()) {
            for (String cId : contradictingIds) {
                if (cId != null && !cId.isBlank()) {
                    Optional<EvidenceItem> cItemOpt = targetInvestigationId != null
                            ? registry.findItemById(cId, targetInvestigationId)
                            : registry.findItemById(cId);
                    cItemOpt.ifPresent(contradictingItems::add);
                }
            }
        }

        if (!contradictingItems.isEmpty()) {
            boolean directRefutation = contradictingItems.stream().anyMatch(item ->
                    item.summary().toLowerCase(Locale.ROOT).contains("post-failure") ||
                    item.summary().toLowerCase(Locale.ROOT).contains("zero database or stack trace overlap") ||
                    item.summary().toLowerCase(Locale.ROOT).contains("cosmetic") ||
                    item.summary().toLowerCase(Locale.ROOT).contains("superseded")
            );

            if (directRefutation) {
                StringBuilder refutationSummary = new StringBuilder();
                for (EvidenceItem cItem : contradictingItems) {
                    refutationSummary.append("[").append(cItem.evidenceId()).append("]: ").append(cItem.summary()).append(" ");
                }
                return claim.withValidation(
                        ClaimValidationStatus.CONTRADICTED,
                        "Claim is refuted by contradictory evidence: " + refutationSummary.toString().trim()
                );
            }
        }

        // 5. Validate logical support: does the evidence actually substantiate the claim statement?
        String claimLower = claim.statement().toLowerCase(Locale.ROOT);
        ValidationSupportResult supportResult = checkLogicalSupport(claimLower, supportingItems);
        if (!supportResult.isSupported()) {
            return claim.withValidation(
                    ClaimValidationStatus.UNSUPPORTED,
                    "Validation failed: " + supportResult.reason()
            );
        }

        // If logical support passed, but non-fatal contradictions exist, mark partially supported
        if (!contradictingItems.isEmpty()) {
            StringBuilder contraSummary = new StringBuilder();
            for (EvidenceItem cItem : contradictingItems) {
                contraSummary.append("[").append(cItem.evidenceId()).append("]: ").append(cItem.summary()).append("; ");
            }
            return claim.withValidation(
                    ClaimValidationStatus.PARTIALLY_SUPPORTED,
                    "Claim has supporting evidence, but is dampened by " + contradictingItems.size() +
                            " contradictory evidence observation(s): " + contraSummary.toString().trim()
            );
        }

        // 6. Fully supported claim
        boolean isWorkingTreeObservation = claimLower.contains("working tree")
                && (claimLower.contains("missing") || claimLower.contains("absent") || claimLower.contains("disappear"));
        String substantiationNote = isWorkingTreeObservation
                ? " (forensically verified observation; deletion process remains unestablished)."
                : " (causally substantiated).";

        return claim.withValidation(
                ClaimValidationStatus.SUPPORTED,
                "Claim successfully validated: Supported by " + supportingItems.size() +
                        " verified evidence item(s) with confirmed integrity hashes" + substantiationNote
        );
    }

    @Override
    public boolean verifyEvidenceIntegrity(EvidenceItem item) {
        if (item == null || item.integrityHash() == null) {
            return false;
        }

        String rawPayload = item.evidenceId() + "|" + item.source() + "|" + item.originalReference() + "|" + item.summary();
        String fullSha256 = computeFullSha256(rawPayload);
        String legacyTruncated = fullSha256.length() >= 16 ? fullSha256.substring(0, 16) : fullSha256;

        if (item.integrityHash().length() == 64) {
            return fullSha256.equalsIgnoreCase(item.integrityHash());
        } else if (item.integrityHash().length() == 16) {
            return legacyTruncated.equalsIgnoreCase(item.integrityHash());
        }
        return false;
    }

    private ValidationSupportResult checkLogicalSupport(String claimStatement, List<EvidenceItem> items) {
        // 0. Reject causal claims citing cosmetic commits or commits with zero heuristic confidence
        boolean assertsCausality = claimStatement.contains("root cause")
                || claimStatement.contains("contributing")
                || claimStatement.contains("caused")
                || claimStatement.contains("triggered")
                || claimStatement.contains("responsible");

        if (assertsCausality) {
            for (EvidenceItem item : items) {
                String summaryLower = item.summary() != null ? item.summary().toLowerCase(Locale.ROOT) : "";
                if (summaryLower.contains("cosmetic")
                        || Boolean.TRUE.equals(item.metadata().get("isCosmetic"))
                        || "true".equalsIgnoreCase(String.valueOf(item.metadata().get("cosmetic")))) {
                    return new ValidationSupportResult(false,
                            "Claim asserts causal responsibility, but cited commit [" + item.evidenceId() + "] is cosmetic with zero functional impact.");
                }
                if (item.metadata().containsKey("confidence")) {
                    try {
                        double conf = Double.parseDouble(String.valueOf(item.metadata().get("confidence")));
                        if (conf <= 0.0) {
                            return new ValidationSupportResult(false,
                                    "Claim asserts causal responsibility, but cited evidence [" + item.evidenceId() + "] has 0% heuristic correlation confidence.");
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 1. Reject claims that confuse working-tree deletions with committed deletions
        boolean assertsCommittedDeletion = (claimStatement.matches(".*\\bcommitted\\s+deletion\\b.*")
                || (claimStatement.matches(".*\\bcommit\\b.*") && claimStatement.matches(".*\\b(deleted|deletion)\\b.*")))
                && !claimStatement.contains("no committed deletion")
                && !claimStatement.contains("without committed deletion")
                && !claimStatement.contains("zero committed deletion");

        if (assertsCommittedDeletion) {
            boolean allWorkingTree = items.stream().allMatch(i -> i.evidenceType() == EvidenceType.WORKING_TREE_CHANGE);
            boolean hasUnstagedMissing = items.stream().anyMatch(i ->
                    i.evidenceType() == EvidenceType.WORKING_TREE_CHANGE &&
                    "UNSTAGED".equalsIgnoreCase(String.valueOf(i.metadata().get("stagingState")))
            );
            if (allWorkingTree || hasUnstagedMissing) {
                return new ValidationSupportResult(false,
                        "Claim asserts committed deletion in repository history, but cited evidence items are uncommitted working tree changes (unstaged file disappearance).");
            }
        }

        // 2. Reject claims asserting an unverified deletion process without system audit evidence
        boolean assertsDeletionProcess = claimStatement.matches(".*\\b(onedrive|antivirus|defender|ide|user\\s+deleted|rm\\s+-rf|external\\s+sync|sync\\s+client|process\\s+deleted|deletion\\s+process)\\b.*");

        if (assertsDeletionProcess) {
            boolean hasSystemAuditEvidence = items.stream().anyMatch(i ->
                    "SYSTEM_AUDIT".equalsIgnoreCase(i.source()) ||
                    "OS_LOG".equalsIgnoreCase(i.source())
            );
            if (!hasSystemAuditEvidence) {
                return new ValidationSupportResult(false,
                        "Claim asserts specific deletion process without corroborating system audit log or OS process tracking evidence. Physical absence proves files are missing from disk, but not which process deleted them.");
            }
        }

        // 3. Claims asserting working tree anomalies / missing files must be backed by WORKING_TREE_CHANGE evidence
        boolean assertsWorkingTreeMissing = claimStatement.contains("working tree")
                && (claimStatement.contains("missing") || claimStatement.contains("absent") || claimStatement.contains("disappear"));

        if (assertsWorkingTreeMissing) {
            boolean hasWorkingTreeEvidence = items.stream().anyMatch(i ->
                    i.evidenceType() == EvidenceType.WORKING_TREE_CHANGE
            );
            if (!hasWorkingTreeEvidence) {
                return new ValidationSupportResult(false,
                        "Claim asserts working tree missing files, but cited evidence contains no WORKING_TREE_CHANGE evidence.");
            }
        }

        // If claim asserts code regression / crash in a file (and is not an uncommitted working tree claim)
        if (!assertsWorkingTreeMissing && (claimStatement.contains("code regression") || claimStatement.contains("stack frame") || claimStatement.contains(".java") || claimStatement.contains(".js") || claimStatement.contains(".py") || claimStatement.contains(".ts"))) {
            boolean hasCodeOrStackEvidence = items.stream().anyMatch(i ->
                    i.evidenceType() == EvidenceType.STACK_FRAME ||
                    i.evidenceType() == EvidenceType.COMMIT_DIFF ||
                    (i.evidenceType() == EvidenceType.COMMIT && !i.summary().toLowerCase().contains("cosmetic"))
            );
            if (!hasCodeOrStackEvidence) {
                return new ValidationSupportResult(false, "Claim asserts code regression, but cited evidence contains no code diff or stack frame evidence.");
            }
        }

        // If claim asserts configuration failure (and is not an uncommitted working tree claim)
        if (!assertsWorkingTreeMissing && (claimStatement.contains("config") || claimStatement.contains("timeout") || claimStatement.contains("connection") || claimStatement.contains("pool"))) {
            boolean hasConfigOrTimeoutEvidence = items.stream().anyMatch(i ->
                    i.evidenceType() == EvidenceType.CONFIG_DIFF ||
                    i.evidenceType() == EvidenceType.ERROR_SIGNATURE ||
                    (i.metadata().containsKey("key") && i.metadata().get("key").toString().toLowerCase().contains("timeout")) ||
                    (i.metadata().containsKey("key") && i.metadata().get("key").toString().toLowerCase().contains("connection")) ||
                    (i.metadata().containsKey("key") && i.metadata().get("key").toString().toLowerCase().contains("pool")) ||
                    (i.metadata().containsKey("signature") && i.metadata().get("signature").toString().toLowerCase().contains("timeout"))
            );
            if (!hasConfigOrTimeoutEvidence) {
                return new ValidationSupportResult(false, "Claim asserts configuration root cause, but cited evidence lacks configuration delta or error signature matches.");
            }
        }

        return new ValidationSupportResult(true, "Supported");
    }

    private static String computeFullSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String computeHash(String input) {
        String full = computeFullSha256(input);
        return full.length() >= 16 ? full.substring(0, 16) : full;
    }

    private record ValidationSupportResult(boolean isSupported, String reason) {}
}
