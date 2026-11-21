package com.traceback.api.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Concurrency integration tests verifying real PostgreSQL 16 transaction and lock semantics:
 * 1. Lock timeout (SQLSTATE 55P03) places transaction in aborted state (SQLSTATE 25P02),
 *    requiring explicit ROLLBACK before subsequent commands succeed in a fresh transaction.
 * 2. Concurrent proposal with identical idempotency_key triggers unique constraint violation (SQLSTATE 23505),
 *    requiring rollback, followed by successful query of the existing proposal in a fresh transaction.
 * 3. Exclusive (FOR UPDATE) vs Shared (FOR SHARE) row-level lock exclusion.
 */
class PostgreSqlRemediationConcurrencyIntegrationTest extends PostgreSqlTestcontainersBase {

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection conn = getIsolatedConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM " + testSchema + ".remediation_operation_events");
            stmt.execute("DELETE FROM " + testSchema + ".remediation_operations");
            stmt.execute("DELETE FROM " + testSchema + ".remediation_target_ref_locks");
            conn.commit();
        }
    }

    @Test
    @DisplayName("SQLSTATE 55P03: Lock timeout triggers aborted transaction (25P02); explicit rollback required before recovery")
    void testLockTimeoutTransactionRollbackAndRecovery_55P03() throws Exception {
        String refName = "refs/heads/main";

        String targetTable = testSchema + ".remediation_target_ref_locks";

        // Seed initial ref lock row
        try (Connection conn = getIsolatedConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + targetTable + " (ref_name, is_frozen, current_fencing_token) VALUES (?, false, 1)")) {
            ps.setString(1, refName);
            ps.executeUpdate();
            conn.commit();
        }

        CountDownLatch thread1HoldingLock = new CountDownLatch(1);
        CountDownLatch thread2AttemptedLock = new CountDownLatch(1);
        CountDownLatch thread1ReleaseLock = new CountDownLatch(1);
        CountDownLatch thread1Committed = new CountDownLatch(1);

        AtomicReference<String> thread2CaughtSqlState = new AtomicReference<>();
        AtomicReference<String> thread2AbortedTransactionSqlState = new AtomicReference<>();
        AtomicReference<Throwable> thread2Exception = new AtomicReference<>();
        AtomicBoolean thread2SuccessfullyRecoveredInNewTx = new AtomicBoolean(false);

        // Thread 1: Acquire exclusive lock and hold it
        Thread t1 = new Thread(() -> {
            try (Connection conn1 = getIsolatedConnection()) {
                conn1.setAutoCommit(false);
                try (PreparedStatement ps1 = conn1.prepareStatement(
                        "SELECT ref_name FROM " + targetTable + " WHERE ref_name = ? FOR UPDATE")) {
                    ps1.setString(1, refName);
                    try (ResultSet rs = ps1.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                    }
                }
                thread1HoldingLock.countDown();

                // Wait until thread 2 has timed out
                thread1ReleaseLock.await(5, TimeUnit.SECONDS);
                conn1.commit();
                thread1Committed.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Thread 2: Set short lock_timeout, attempt to lock same row, verify 55P03, verify 25P02, rollback, then succeed
        Thread t2 = new Thread(() -> {
            try (Connection conn2 = getIsolatedConnection()) {
                thread1HoldingLock.await(5, TimeUnit.SECONDS);

                // Set lock_timeout to 200 milliseconds
                try (Statement stmt = conn2.createStatement()) {
                    stmt.execute("SET LOCAL lock_timeout = '200ms'");
                }

                // Attempt to acquire lock on the same row -> Expect 55P03 lock_timeout
                try (PreparedStatement ps2 = conn2.prepareStatement(
                        "SELECT ref_name FROM " + targetTable + " WHERE ref_name = ? FOR UPDATE")) {
                    ps2.setString(1, refName);
                    ps2.executeQuery();
                    fail("Should have failed with lock_timeout (SQLSTATE 55P03)");
                } catch (SQLException e) {
                    thread2CaughtSqlState.set(e.getSQLState());
                    assertThat(e.getSQLState()).isEqualTo("55P03");
                }

                // In PostgreSQL, connection is now in ABORTED transaction state (25P02).
                // Any subsequent query without ROLLBACK must fail with 25P02.
                try (Statement stmt = conn2.createStatement()) {
                    stmt.execute("SELECT 1");
                    fail("Should have failed with aborted transaction state (SQLSTATE 25P02)");
                } catch (SQLException e) {
                    thread2AbortedTransactionSqlState.set(e.getSQLState());
                    assertThat(e.getSQLState()).isEqualTo("25P02");
                }

                thread2AttemptedLock.countDown();

                // Explicit ROLLBACK restores connection to valid state
                conn2.rollback();

                // Wait for Thread 1 to commit and release lock
                thread1ReleaseLock.countDown();
                thread1Committed.await(5, TimeUnit.SECONDS);

                // Thread 2 starts a fresh transaction and succeeds
                conn2.setAutoCommit(false);
                try (PreparedStatement ps2 = conn2.prepareStatement(
                        "SELECT ref_name FROM " + targetTable + " WHERE ref_name = ? FOR UPDATE")) {
                    ps2.setString(1, refName);
                    try (ResultSet rs = ps2.executeQuery()) {
                        if (rs.next()) {
                            thread2SuccessfullyRecoveredInNewTx.set(true);
                        }
                    }
                    conn2.commit();
                }
            } catch (Throwable e) {
                thread2Exception.set(e);
                e.printStackTrace();
            }
        });

        t1.start();
        t2.start();

        t1.join(5000);
        t2.join(5000);

        assertThat(thread2CaughtSqlState.get()).isEqualTo("55P03");
        assertThat(thread2AbortedTransactionSqlState.get()).isEqualTo("25P02");
        assertThat(thread2SuccessfullyRecoveredInNewTx.get()).isTrue();
    }

    @Test
    @DisplayName("SQLSTATE 23505: Duplicate idempotency_key triggers unique_violation; rollback + fresh lookup succeeds")
    void testDuplicateProposalIdempotencyRaceHandling_23505() throws Exception {
        String idempotencyKey = "idem-test-race-" + UUID.randomUUID();
        UUID opId1 = UUID.randomUUID();
        UUID opId2 = UUID.randomUUID();

        CountDownLatch thread1Inserted = new CountDownLatch(1);
        CountDownLatch thread2Failed = new CountDownLatch(1);

        AtomicReference<String> thread2CaughtSqlState = new AtomicReference<>();
        AtomicReference<String> retrievedOperationId = new AtomicReference<>();

        // Thread 1: Inserts proposal with idempotencyKey and commits
        Thread t1 = new Thread(() -> {
            try (Connection conn1 = getIsolatedConnection()) {
                conn1.setAutoCommit(false);
                insertProposal(conn1, opId1, idempotencyKey, "refs/heads/main");
                conn1.commit();
                thread1Inserted.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Thread 2: Concurrently attempts to insert the same idempotencyKey -> Expect 23505
        Thread t2 = new Thread(() -> {
            try (Connection conn2 = getIsolatedConnection()) {
                thread1Inserted.await(5, TimeUnit.SECONDS);

                conn2.setAutoCommit(false);
                try {
                    insertProposal(conn2, opId2, idempotencyKey, "refs/heads/main");
                    conn2.commit();
                    fail("Should have failed with unique constraint violation (SQLSTATE 23505)");
                } catch (SQLException e) {
                    thread2CaughtSqlState.set(e.getSQLState());
                    assertThat(e.getSQLState()).isEqualTo("23505");
                    // Must execute rollback due to aborted transaction
                    conn2.rollback();
                }
                thread2Failed.countDown();

                // Thread 2 starts a fresh transaction and looks up the existing proposal
                conn2.setAutoCommit(false);
                try (PreparedStatement ps = conn2.prepareStatement(
                        "SELECT id FROM " + testSchema + ".remediation_operations WHERE idempotency_key = ?")) {
                    ps.setString(1, idempotencyKey);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            retrievedOperationId.set(rs.getString("id"));
                        }
                    }
                    conn2.commit();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        t1.start();
        t2.start();

        t1.join(5000);
        t2.join(5000);

        assertThat(thread2CaughtSqlState.get()).isEqualTo("23505");
        // Must retrieve Thread 1's committed proposal ID, not Thread 2's failed one
        assertThat(retrievedOperationId.get()).isEqualTo(opId1.toString());
    }

    @Test
    @DisplayName("Pessimistic Locking: FOR UPDATE excludes concurrent FOR SHARE until transaction commit")
    void testLevel0RefLockExclusiveVsSharedExclusion() throws Exception {
        String refName = "refs/heads/feature-branch";
        String targetTable = testSchema + ".remediation_target_ref_locks";

        try (Connection conn = getIsolatedConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO " + targetTable + " (ref_name, is_frozen, current_fencing_token) VALUES (?, false, 1)")) {
            ps.setString(1, refName);
            ps.executeUpdate();
            conn.commit();
        }

        CountDownLatch thread1Locked = new CountDownLatch(1);
        CountDownLatch thread2BlockedThenAcquired = new CountDownLatch(1);
        AtomicBoolean lockContentionObserved = new AtomicBoolean(false);

        // Thread 1: Acquire FOR UPDATE and hold for 400ms
        Thread t1 = new Thread(() -> {
            try (Connection conn1 = getIsolatedConnection()) {
                conn1.setAutoCommit(false);
                try (PreparedStatement ps1 = conn1.prepareStatement(
                        "SELECT ref_name FROM " + targetTable + " WHERE ref_name = ? FOR UPDATE")) {
                    ps1.setString(1, refName);
                    try (ResultSet rs = ps1.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                    }
                }
                thread1Locked.countDown();
                Thread.sleep(400);
                conn1.commit();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        // Thread 2: Attempts FOR SHARE immediately after Thread 1 locks; should block until Thread 1 commits
        Thread t2 = new Thread(() -> {
            try (Connection conn2 = getIsolatedConnection()) {
                thread1Locked.await(5, TimeUnit.SECONDS);
                long startTime = System.currentTimeMillis();

                conn2.setAutoCommit(false);
                try (PreparedStatement ps2 = conn2.prepareStatement(
                        "SELECT ref_name FROM " + targetTable + " WHERE ref_name = ? FOR SHARE")) {
                    ps2.setString(1, refName);
                    try (ResultSet rs = ps2.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                    }
                }
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= 300) {
                    lockContentionObserved.set(true);
                }
                conn2.commit();
                thread2BlockedThenAcquired.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        t1.start();
        t2.start();

        t1.join(5000);
        t2.join(5000);

        assertThat(lockContentionObserved.get()).isTrue();
    }

    private void insertProposal(Connection conn, UUID id, String idempotencyKey, String targetRef) throws SQLException {
        String sql = "INSERT INTO " + testSchema + ".remediation_operations (" +
                "id, idempotency_key, repository_path, target_ref, expected_old_sha, " +
                "proposed_new_sha, operation_type, status, initiated_by" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, idempotencyKey);
            ps.setString(3, "/repos/test-repo");
            ps.setString(4, targetRef);
            ps.setString(5, "a".repeat(40));
            ps.setString(6, "b".repeat(40));
            ps.setString(7, "BRANCH_PATCH_PROMOTION");
            ps.setString(8, "VALIDATED");
            ps.setString(9, "admin-user");
            ps.executeUpdate();
        }
    }
}
