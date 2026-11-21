package com.traceback.api.remediation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceback.api.TracebackApplication;
import com.traceback.core.domain.AgentReportEntity;
import com.traceback.core.domain.ClaimEntity;
import com.traceback.core.investigation.InvestigationService;
import com.traceback.core.repository.InvestigationAgentReportRepository;
import com.traceback.core.repository.InvestigationClaimRepository;
import com.traceback.core.repository.InvestigationEvidenceRepository;
import com.traceback.core.validation.ClaimValidationStatus;
import com.traceback.core.validation.InvestigationClaim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Production-Safe Stage 4 Claim Revalidation Runner.
 *
 * Designed specifically for Phase 2.14+ live execution safety.
 *
 * Safety Guarantees:
 * 1. WebApplicationType.NONE: No web server is started; port 8080 remains unbound.
 * 2. spring.flyway.enabled=false: Flyway migrations are strictly disabled.
 * 3. Exact Database Name Guard: Asserts current_database() matches --target-db argument.
 * 4. Exhaustive Precondition Verification:
 *    - Exactly 79 replacement evidence IDs and SHA-256 hashes match replacement_manifest_79.json.
 *    - Exactly 6 preserved active configuration evidence IDs match manifest_preserved_6.json.
 *    - Exactly 135 evidence records (56 foreign + 79 copied) in quarantine_evidence_phase211 match manifests.
 *    - Zero unexpected active evidence IDs (total active evidence == 85).
 *    - 17 claim primary keys match claim manifest.
 *    - Agent report ID matches REP-e186d4fc.
 * 5. Controlled Transaction: Revalidation and verification execute within an explicit transaction.
 * 6. Strict Assertions: Verifies exactly 17 claims, 2 SUPPORTED, 15 VALIDATION_ERROR, and report update.
 * 7. Explicit Rollback on Failure: Any discrepancy triggers complete rollback and non-zero exit.
 */
public class ProductionStage4ClaimRevalidationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionStage4ClaimRevalidationRunner.class);
    private static final UUID DEFAULT_INVESTIGATION_ID = UUID.fromString("7b9e9117-40da-47f6-a88b-32abc03dd059");
    private static final String EXPECTED_REPORT_ID = "REP-e186d4fc";

    public static void main(String[] args) {
        log.info("==================================================================");
        log.info("TraceBack Stage 4 Production Claim Revalidation Runner");
        log.info("==================================================================");

        Map<String, String> parsedArgs = parseArgs(args);
        String targetDb = parsedArgs.get("target-db");
        String invIdStr = parsedArgs.getOrDefault("investigation-id", DEFAULT_INVESTIGATION_ID.toString());
        String manifestDirArg = parsedArgs.get("manifest-dir");

        String commitArg = parsedArgs.get("commit");
        String dryRunArg = parsedArgs.get("dry-run");

        boolean commit;
        if (commitArg != null && dryRunArg != null) {
            log.error("CRITICAL SAFETY ABORT: Conflicting authorization arguments: cannot specify both --commit and --dry-run.");
            throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Conflicting authorization arguments: cannot specify both --commit and --dry-run.");
        } else if (commitArg != null) {
            if ("true".equalsIgnoreCase(commitArg)) {
                commit = true;
            } else if ("false".equalsIgnoreCase(commitArg)) {
                commit = false;
            } else {
                log.error("CRITICAL SAFETY ABORT: Invalid value for --commit: '{}'. Must be 'true' or 'false'.", commitArg);
                throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Invalid value for --commit: '" + commitArg + "'. Must be 'true' or 'false'.");
            }
        } else if (dryRunArg != null) {
            if ("true".equalsIgnoreCase(dryRunArg) || "false".equalsIgnoreCase(dryRunArg)) {
                commit = false; // Even --dry-run=false without explicit --commit=true remains non-committing dry-run
            } else {
                log.error("CRITICAL SAFETY ABORT: Invalid value for --dry-run: '{}'. Must be 'true' or 'false'.", dryRunArg);
                throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Invalid value for --dry-run: '" + dryRunArg + "'. Must be 'true' or 'false'.");
            }
        } else {
            // Default execution is STRICT DRY-RUN
            commit = false;
        }

        boolean dryRun = !commit;

        log.info("******************************************************************");
        if (commit) {
            log.info("EXECUTION MODE: COMMITTED PRODUCTION MUTATION (--commit=true AUTHORIZED)");
        } else {
            log.info("EXECUTION MODE: NON-MUTATING DRY-RUN (TRANSACTION WILL ROLL BACK)");
        }
        log.info("******************************************************************");

        if (targetDb == null || targetDb.isBlank()) {
            log.error("CRITICAL SAFETY ABORT: Missing required argument --target-db=<dbname>");
            throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Missing required argument --target-db=<dbname>");
        }

        UUID investigationId;
        try {
            investigationId = UUID.fromString(invIdStr);
        } catch (IllegalArgumentException e) {
            log.error("CRITICAL SAFETY ABORT: Invalid investigation UUID: '{}'", invIdStr);
            throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Invalid investigation UUID: '" + invIdStr + "'");
        }

        File manifestDir = resolveManifestDir(manifestDirArg);
        log.info("Using manifest directory: {}", manifestDir.getAbsolutePath());

        // Configure Spring Boot with zero web server and zero flyway migrations
        System.setProperty("spring.flyway.enabled", "false");
        System.setProperty("spring.main.web-application-type", "none");
        ConfigurableApplicationContext ctx = null;
        try {
            ctx = new SpringApplicationBuilder(TracebackApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "spring.flyway.enabled=false",
                            "spring.jpa.hibernate.ddl-auto=validate"
                    )
                    .run(args);

            DataSource dataSource = ctx.getBean(DataSource.class);
            ObjectMapper objectMapper = ctx.getBean(ObjectMapper.class);

            // Step 1: Verify connected database matches target-db
            verifyTargetDatabase(dataSource, targetDb);

            // Step 2: Verify all Stage 3 preconditions in database
            verifyPreconditions(dataSource, objectMapper, investigationId, manifestDir);

            // Step 3: Execute Stage 4 inside a transaction
            PlatformTransactionManager txManager = ctx.getBean(PlatformTransactionManager.class);
            InvestigationService investigationService = ctx.getBean(InvestigationService.class);
            InvestigationClaimRepository claimRepo = ctx.getBean(InvestigationClaimRepository.class);
            InvestigationAgentReportRepository reportRepo = ctx.getBean(InvestigationAgentReportRepository.class);

            executeStage4WithVerification(txManager, investigationService, claimRepo, reportRepo, objectMapper,
                    investigationId, dryRun);

            log.info("==================================================================");
            log.info("STAGE 4 REVALIDATION RUNNER COMPLETED SUCCESSFULLY (EXIT 0)");
            log.info("==================================================================");
            System.exit(0);

        } catch (Throwable t) {
            log.error("FATAL ERROR during Stage 4 execution: {}", t.getMessage(), t);
            System.exit(1);
        } finally {
            if (ctx != null && ctx.isActive()) {
                ctx.close();
            }
        }
    }

    private static final Set<String> ALLOWED_ARG_NAMES = Set.of(
            "target-db",
            "investigation-id",
            "manifest-dir",
            "commit",
            "dry-run"
    );

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        if (args == null || args.length == 0) {
            return map;
        }

        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (!arg.startsWith("--")) {
                log.error("CRITICAL SAFETY ABORT: Malformed CLI argument (must start with '--'): '{}'", arg);
                throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Malformed CLI argument (must start with '--'): '" + arg + "'");
            }

            int eq = arg.indexOf('=');
            String key;
            String val;
            if (eq > 2) {
                key = arg.substring(2, eq).trim().toLowerCase();
                val = arg.substring(eq + 1).trim();
            } else if (eq == 2) {
                log.error("CRITICAL SAFETY ABORT: Malformed argument with empty name: '{}'", arg);
                throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Malformed argument with empty name: '" + arg + "'");
            } else {
                key = arg.substring(2).trim().toLowerCase();
                val = "true";
            }

            if (key.isEmpty()) {
                log.error("CRITICAL SAFETY ABORT: Malformed argument with empty name: '{}'", arg);
                throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Malformed argument with empty name: '" + arg + "'");
            }

            if (!ALLOWED_ARG_NAMES.contains(key)) {
                log.error("CRITICAL SAFETY ABORT: Unrecognized CLI argument: '--{}'. Allowed arguments are: {}", key, ALLOWED_ARG_NAMES);
                throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Unrecognized CLI argument: '--" + key + "'. Allowed arguments are: " + ALLOWED_ARG_NAMES);
            }

            if (map.containsKey(key)) {
                log.error("CRITICAL SAFETY ABORT: Duplicate argument: '--{}'", key);
                throw new IllegalArgumentException("CRITICAL SAFETY ABORT: Duplicate argument: '--" + key + "'");
            }

            map.put(key, val);
        }
        return map;
    }

    private static File resolveManifestDir(String manifestDirArg) {
        if (manifestDirArg != null && !manifestDirArg.isBlank()) {
            File d = new File(manifestDirArg);
            if (d.isDirectory()) return d;
        }
        File d1 = new File("scratch");
        if (d1.isDirectory()) return d1;
        File d2 = new File("../scratch");
        if (d2.isDirectory()) return d2;
        throw new IllegalStateException("Could not resolve scratch manifest directory!");
    }

    private static void verifyTargetDatabase(DataSource dataSource, String expectedDb) throws Exception {
        log.info("Verifying database connection...");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_database(), current_user, inet_server_port()")) {

            if (!rs.next()) {
                throw new IllegalStateException("Failed to query current_database()");
            }
            String currentDb = rs.getString(1);
            String currentUser = rs.getString(2);
            int port = rs.getInt(3);

            log.info("Connected to database: '{}', user: '{}', port: {}", currentDb, currentUser, port);

            // HARD SAFETY GUARD 1: Explicitly reject protected database traceback_test_db
            if ("traceback_test_db".equalsIgnoreCase(currentDb) || "traceback_test_db".equalsIgnoreCase(expectedDb)) {
                throw new SecurityException("CRITICAL SAFETY VIOLATION: Target database 'traceback_test_db' is strictly protected and CANNOT be targeted for remediation!");
            }

            // HARD SAFETY GUARD 2: Only approved databases permitted
            boolean isApprovedCurrent = "traceback_audit_db".equalsIgnoreCase(currentDb)
                    || "traceback_db".equalsIgnoreCase(currentDb)
                    || currentDb.toLowerCase().startsWith("traceback_recov_")
                    || currentDb.toLowerCase().startsWith("traceback_runbook_test");
            boolean isApprovedExpected = "traceback_audit_db".equalsIgnoreCase(expectedDb)
                    || "traceback_db".equalsIgnoreCase(expectedDb)
                    || expectedDb.toLowerCase().startsWith("traceback_recov_")
                    || expectedDb.toLowerCase().startsWith("traceback_runbook_test");
            if (!isApprovedCurrent || !isApprovedExpected) {
                throw new SecurityException(String.format("CRITICAL SAFETY VIOLATION: Database '%s' / '%s' is not in approved whitelist ('traceback_db', 'traceback_audit_db', 'traceback_recov_*', 'traceback_runbook_test*')!", currentDb, expectedDb));
            }

            // HARD SAFETY GUARD 3: Exact match between connected and requested database
            if (!currentDb.equalsIgnoreCase(expectedDb)) {
                throw new SecurityException(String.format(
                        "CRITICAL SAFETY VIOLATION: Connected to database '%s', but --target-db argument specified '%s'. ABORTING IMMEDIATELY!",
                        currentDb, expectedDb
                ));
            }
            log.info("Target database '{}' verified successfully against approved whitelist.", currentDb);
        }
    }

    public static void verifyPreconditions(DataSource dataSource, ObjectMapper objectMapper, UUID investigationId, File manifestDir) throws Exception {
        log.info("Verifying Stage 3 completion preconditions...");

        // Load manifests
        File preservedFile = new File(manifestDir, "manifest_preserved_6.json");
        File replacementFile = new File(manifestDir, "replacement_manifest_79.json");
        File foreignFile = new File(manifestDir, "manifest_foreign_56.json");
        File copiedFile = new File(manifestDir, "manifest_copied_79.json");
        File claimsBaselineFile = new File(manifestDir, "manifest_claims_and_reports_baseline.json");

        if (!preservedFile.exists() || !replacementFile.exists() || !foreignFile.exists() || !copiedFile.exists() || !claimsBaselineFile.exists()) {
            throw new IllegalStateException("One or more required manifest files are missing in " + manifestDir.getAbsolutePath());
        }

        JsonNode preservedJson = objectMapper.readTree(preservedFile);
        JsonNode replacementJson = objectMapper.readTree(replacementFile);
        JsonNode foreignJson = objectMapper.readTree(foreignFile);
        JsonNode copiedJson = objectMapper.readTree(copiedFile);
        JsonNode claimsBaselineJson = objectMapper.readTree(claimsBaselineFile);

        try (Connection conn = dataSource.getConnection()) {

            // 1. Verify Active Evidence in investigation_evidence
            log.info("1. Verifying active evidence records in investigation_evidence...");
            Map<String, EvidenceSnapshot> activeMap = new HashMap<>();
            String evSql = "SELECT id, evidence_id, integrity_hash FROM investigation_evidence WHERE investigation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(evSql)) {
                ps.setObject(1, investigationId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = (UUID) rs.getObject("id");
                        String evId = rs.getString("evidence_id");
                        String hash = rs.getString("integrity_hash");
                        activeMap.put(evId, new EvidenceSnapshot(id, evId, hash));
                    }
                }
            }

            if (activeMap.size() != 85) {
                throw new IllegalStateException(String.format(
                        "PRECONDITION FAILED: Expected exactly 85 active evidence records for investigation %s (post-Stage 3 state), but found %d.",
                        investigationId, activeMap.size()
                ));
            }

            // Verify 6 Preserved active configuration records
            for (JsonNode node : preservedJson) {
                String evId = node.get("evidence_id").asText();
                UUID expectedId = UUID.fromString(node.get("id").asText());
                String expectedHash = node.get("integrity_hash").asText();

                EvidenceSnapshot snap = activeMap.get(evId);
                if (snap == null) {
                    throw new IllegalStateException("PRECONDITION FAILED: Preserved evidence record missing from active evidence: " + evId);
                }
                if (!snap.id.equals(expectedId)) {
                    throw new IllegalStateException(String.format("PRECONDITION FAILED: Preserved evidence %s PK mismatch: expected %s, found %s", evId, expectedId, snap.id));
                }
                if (!snap.hash.equalsIgnoreCase(expectedHash)) {
                    throw new IllegalStateException(String.format("PRECONDITION FAILED: Preserved evidence %s hash mismatch: expected %s, found %s", evId, expectedHash, snap.hash));
                }
            }
            log.info("Verified all 6 preserved active configuration evidence records and hashes.");

            // Verify 79 Replacement records
            for (JsonNode node : replacementJson) {
                String evId = node.get("evidence_id").asText();
                UUID expectedId = UUID.fromString(node.get("id").asText());
                String expectedHash = node.get("integrity_hash").asText();

                EvidenceSnapshot snap = activeMap.get(evId);
                if (snap == null) {
                    throw new IllegalStateException("PRECONDITION FAILED: Replacement evidence record missing from active evidence: " + evId);
                }
                if (!snap.id.equals(expectedId)) {
                    throw new IllegalStateException(String.format("PRECONDITION FAILED: Replacement evidence %s PK mismatch: expected %s, found %s", evId, expectedId, snap.id));
                }
                if (!snap.hash.equalsIgnoreCase(expectedHash)) {
                    throw new IllegalStateException(String.format("PRECONDITION FAILED: Replacement evidence %s hash mismatch: expected %s, found %s", evId, expectedHash, snap.hash));
                }
            }
            log.info("Verified all 79 replacement evidence records and SHA-256 hashes.");

            // 2. Verify Quarantine Table: exactly 135 records (56 foreign + 79 copied)
            log.info("2. Verifying quarantine records in investigation_evidence_quarantine...");
            Set<UUID> quarantineIds = new HashSet<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT original_id FROM investigation_evidence_quarantine")) {
                while (rs.next()) {
                    quarantineIds.add((UUID) rs.getObject("original_id"));
                }
            }

            if (quarantineIds.size() != 135) {
                throw new IllegalStateException(String.format(
                        "PRECONDITION FAILED: Expected exactly 135 quarantine evidence records, but found %d.",
                        quarantineIds.size()
                ));
            }

            for (JsonNode node : foreignJson) {
                UUID expectedId = UUID.fromString(node.get("id").asText());
                if (!quarantineIds.contains(expectedId)) {
                    throw new IllegalStateException("PRECONDITION FAILED: Foreign evidence PK missing from quarantine table: " + expectedId);
                }
            }
            log.info("Verified all 56 foreign evidence records exist in quarantine table.");

            for (JsonNode node : copiedJson) {
                UUID expectedId = UUID.fromString(node.get("id").asText());
                if (!quarantineIds.contains(expectedId)) {
                    throw new IllegalStateException("PRECONDITION FAILED: Copied evidence PK missing from quarantine table: " + expectedId);
                }
            }
            log.info("Verified all 79 copied evidence records exist in quarantine table.");

            // 3. Verify Claim Primary Keys: exactly 17 matching baseline manifest
            log.info("3. Verifying claim primary keys in investigation_claims...");
            Map<UUID, String> dbClaimMap = new HashMap<>();
            String clmSql = "SELECT id, claim_id FROM investigation_claims WHERE investigation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(clmSql)) {
                ps.setObject(1, investigationId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        dbClaimMap.put((UUID) rs.getObject("id"), rs.getString("claim_id"));
                    }
                }
            }

            JsonNode manifestClaims = claimsBaselineJson.get("claims");
            if (manifestClaims == null || manifestClaims.size() != 17) {
                throw new IllegalStateException("Baseline manifest claims array must contain exactly 17 elements");
            }

            if (dbClaimMap.size() != 17) {
                throw new IllegalStateException(String.format(
                        "PRECONDITION FAILED: Expected exactly 17 claims in DB for %s, found %d",
                        investigationId, dbClaimMap.size()
                ));
            }

            for (JsonNode node : manifestClaims) {
                UUID expectedId = UUID.fromString(node.get("id").asText());
                String expectedClaimId = node.get("claim_id").asText();
                String actualClaimId = dbClaimMap.get(expectedId);
                if (actualClaimId == null) {
                    throw new IllegalStateException("PRECONDITION FAILED: Claim PK missing in database: " + expectedId);
                }
                if (!actualClaimId.equals(expectedClaimId)) {
                    throw new IllegalStateException(String.format("PRECONDITION FAILED: Claim ID mismatch for PK %s: expected %s, found %s", expectedId, expectedClaimId, actualClaimId));
                }
            }
            log.info("Verified all 17 claim primary keys and claim IDs match baseline manifest.");

            // 4. Verify Agent Report ID matches REP-e186d4fc
            log.info("4. Verifying agent report ID...");
            String rptSql = "SELECT report_id FROM investigation_agent_reports WHERE investigation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(rptSql)) {
                ps.setObject(1, investigationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("PRECONDITION FAILED: No agent report found for investigation " + investigationId);
                    }
                    String reportId = rs.getString("report_id");
                    if (!EXPECTED_REPORT_ID.equals(reportId)) {
                        throw new IllegalStateException(String.format("PRECONDITION FAILED: Expected report ID %s, found %s", EXPECTED_REPORT_ID, reportId));
                    }
                    if (rs.next()) {
                        throw new IllegalStateException("PRECONDITION FAILED: Multiple agent reports found for investigation " + investigationId);
                    }
                }
            }
            log.info("Verified agent report ID matches expected {}.", EXPECTED_REPORT_ID);
        }

        log.info("ALL PRECONDITIONS SUCCESSFULLY SATISFIED.");
    }

    private static void executeStage4WithVerification(
            PlatformTransactionManager txManager,
            InvestigationService investigationService,
            InvestigationClaimRepository claimRepo,
            InvestigationAgentReportRepository reportRepo,
            ObjectMapper objectMapper,
            UUID investigationId,
            boolean dryRun) throws Exception {

        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("Stage4RevalidationTx");
        def.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRED);

        TransactionStatus status = txManager.getTransaction(def);
        log.info("Started transaction: Stage4RevalidationTx (dryRun={})", dryRun);

        try {
            // Step 1: Execute revalidateInvestigationClaims
            log.info("Invoking investigationService.revalidateInvestigationClaims({})...", investigationId);
            List<InvestigationClaim> revalidated = investigationService.revalidateInvestigationClaims(investigationId);

            if (revalidated == null || revalidated.size() != 17) {
                throw new IllegalStateException(String.format(
                        "ASSERTION FAILED: Expected 17 revalidated claims, but got %d",
                        revalidated == null ? 0 : revalidated.size()
                ));
            }
            log.info("Revalidated {} claims via InvestigationService.", revalidated.size());

            // Step 2: Verify domain claim statuses
            Map<String, InvestigationClaim> domainMap = revalidated.stream()
                    .collect(Collectors.toMap(InvestigationClaim::claimId, c -> c));

            InvestigationClaim wtAnomaly = domainMap.get("CLM-WT-ANOMALY");
            if (wtAnomaly == null || wtAnomaly.status() != ClaimValidationStatus.SUPPORTED) {
                throw new IllegalStateException("ASSERTION FAILED: CLM-WT-ANOMALY is not SUPPORTED!");
            }
            if (wtAnomaly.supportingEvidenceIds().size() != 79) {
                throw new IllegalStateException("ASSERTION FAILED: CLM-WT-ANOMALY supporting evidence count is not 79!");
            }

            InvestigationClaim agentPrimary = domainMap.get("CLAIM-AGENT-PRIMARY-7b9e9117");
            if (agentPrimary == null || agentPrimary.status() != ClaimValidationStatus.SUPPORTED) {
                throw new IllegalStateException("ASSERTION FAILED: CLAIM-AGENT-PRIMARY-7b9e9117 is not SUPPORTED!");
            }
            if (agentPrimary.supportingEvidenceIds().size() != 79) {
                throw new IllegalStateException("ASSERTION FAILED: CLAIM-AGENT-PRIMARY-7b9e9117 supporting evidence count is not 79!");
            }

            long errorCount = revalidated.stream()
                    .filter(c -> !c.claimId().equals("CLM-WT-ANOMALY") && !c.claimId().equals("CLAIM-AGENT-PRIMARY-7b9e9117"))
                    .filter(c -> c.status() == ClaimValidationStatus.VALIDATION_ERROR)
                    .count();
            if (errorCount != 15) {
                throw new IllegalStateException("ASSERTION FAILED: Expected 15 VALIDATION_ERROR claims, found " + errorCount);
            }
            log.info("Domain claim statuses verified: 2 SUPPORTED, 15 VALIDATION_ERROR.");

            // Step 3: Verify persisted ClaimEntity records
            List<ClaimEntity> persistedClaims = claimRepo.findByInvestigationId(investigationId);
            if (persistedClaims.size() != 17) {
                throw new IllegalStateException("ASSERTION FAILED: Expected 17 persisted claims in database, found " + persistedClaims.size());
            }

            Map<String, ClaimEntity> entityMap = persistedClaims.stream()
                    .collect(Collectors.toMap(ClaimEntity::getClaimId, c -> c));

            if (!"SUPPORTED".equals(entityMap.get("CLM-WT-ANOMALY").getValidationStatus())) {
                throw new IllegalStateException("ASSERTION FAILED: Persisted CLM-WT-ANOMALY status is not SUPPORTED");
            }
            if (!"SUPPORTED".equals(entityMap.get("CLAIM-AGENT-PRIMARY-7b9e9117").getValidationStatus())) {
                throw new IllegalStateException("ASSERTION FAILED: Persisted CLAIM-AGENT-PRIMARY-7b9e9117 status is not SUPPORTED");
            }
            log.info("Persisted ClaimEntity records verified in database.");

            // Step 4: Verify agent report validated_claims
            Optional<AgentReportEntity> reportOpt = reportRepo.findLatestByInvestigationId(investigationId);
            if (reportOpt.isEmpty()) {
                throw new IllegalStateException("ASSERTION FAILED: Agent report not found for investigation " + investigationId);
            }
            AgentReportEntity report = reportOpt.get();
            if (!EXPECTED_REPORT_ID.equals(report.getReportId())) {
                throw new IllegalStateException(String.format(
                        "ASSERTION FAILED: Expected report ID %s, but found %s",
                        EXPECTED_REPORT_ID, report.getReportId()
                ));
            }

            List<Map<String, Object>> validatedList = objectMapper.readValue(
                    report.getValidatedClaims(),
                    new TypeReference<>() {}
            );
            if (validatedList.size() != 17) {
                throw new IllegalStateException("ASSERTION FAILED: Agent report validated_claims list size is not 17, got " + validatedList.size());
            }

            Map<String, Map<String, Object>> reportMap = validatedList.stream()
                    .collect(Collectors.toMap(m -> (String) m.get("claimId"), m -> m));

            if (!"SUPPORTED".equals(reportMap.get("CLM-WT-ANOMALY").get("status"))) {
                throw new IllegalStateException("ASSERTION FAILED: Report CLM-WT-ANOMALY status is not SUPPORTED");
            }
            if (!"SUPPORTED".equals(reportMap.get("CLAIM-AGENT-PRIMARY-7b9e9117").get("status"))) {
                throw new IllegalStateException("ASSERTION FAILED: Report CLAIM-AGENT-PRIMARY-7b9e9117 status is not SUPPORTED");
            }
            log.info("Agent report validated_claims verified in database: 17 items, 2 SUPPORTED.");

            if (dryRun) {
                log.info("DRY-RUN mode requested: Rolling back transaction...");
                txManager.rollback(status);
                log.info("DRY-RUN: Transaction rollback successful.");
            } else {
                log.info("Committing transaction...");
                txManager.commit(status);
                log.info("Transaction committed successfully.");
            }

        } catch (Exception e) {
            log.error("Exception in Stage 4 execution. Triggering rollback...", e);
            if (!status.isCompleted()) {
                txManager.rollback(status);
                log.info("Transaction rolled back cleanly due to exception.");
            }
            throw e;
        }
    }

    private static record EvidenceSnapshot(UUID id, String evidenceId, String hash) {}
}
