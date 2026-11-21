package com.traceback.core.remediation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GitRepositoryLockManagerTest {

    @TempDir
    Path tempDir;

    private Path repo1;
    private Path repo2;
    private Path lockDir;
    private DefaultGitRepositoryLockManager lockManager;

    @BeforeEach
    void setUp() throws Exception {
        repo1 = tempDir.resolve("repo1");
        Files.createDirectories(repo1);

        repo2 = tempDir.resolve("repo2");
        Files.createDirectories(repo2);

        lockDir = tempDir.resolve("locks");
        Files.createDirectories(lockDir);

        lockManager = new DefaultGitRepositoryLockManager(lockDir, Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Two threads in same JVM contending for one repository lock: second thread waits until first releases")
    void twoThreadsInSameJvmContendingForOneLock() throws Exception {
        CountDownLatch thread1Acquired = new CountDownLatch(1);
        CountDownLatch thread1Release = new CountDownLatch(1);
        CountDownLatch thread2Acquired = new CountDownLatch(1);
        AtomicBoolean thread2HeldSimultaneously = new AtomicBoolean(false);

        Thread t1 = new Thread(() -> {
            try (AutoCloseableLock lock = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(5))) {
                assertTrue(lock.isValid());
                thread1Acquired.countDown();
                thread1Release.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Thread 1 failed: " + e.getMessage());
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                thread1Acquired.await(5, TimeUnit.SECONDS);
                try (AutoCloseableLock lock = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(5))) {
                    if (thread1Release.getCount() > 0) {
                        thread2HeldSimultaneously.set(true);
                    }
                    thread2Acquired.countDown();
                }
            } catch (Exception e) {
                fail("Thread 2 failed: " + e.getMessage());
            }
        });

        t1.start();
        assertTrue(thread1Acquired.await(5, TimeUnit.SECONDS));

        t2.start();
        // Brief sleep to ensure t2 is blocked waiting for lock
        Thread.sleep(150);

        assertFalse(thread2HeldSimultaneously.get());
        thread1Release.countDown(); // Allow thread 1 to release

        assertTrue(thread2Acquired.await(5, TimeUnit.SECONDS));
        assertFalse(thread2HeldSimultaneously.get());

        t1.join(3000);
        t2.join(3000);
    }

    @Test
    @DisplayName("Two different repositories can acquire independent locks concurrently without contention")
    void twoDifferentRepositoriesAcquiringIndependentLocks() throws Exception {
        CountDownLatch bothLocked = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try (AutoCloseableLock lock1 = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(2))) {
                assertTrue(lock1.isValid());
                bothLocked.countDown();
                bothLocked.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Thread 1 failed: " + e.getMessage());
            }
        });

        Thread t2 = new Thread(() -> {
            try (AutoCloseableLock lock2 = lockManager.acquireLock(repo2.toString(), Duration.ofSeconds(2))) {
                assertTrue(lock2.isValid());
                bothLocked.countDown();
                bothLocked.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Thread 2 failed: " + e.getMessage());
            }
        });

        t1.start();
        t2.start();

        assertTrue(bothLocked.await(5, TimeUnit.SECONDS), "Both repositories should be locked simultaneously");
        t1.join(3000);
        t2.join(3000);
    }

    @Test
    @DisplayName("Lock acquisition times out when lock is held longer than timeout")
    void lockTimeoutBehavior() throws Exception {
        CountDownLatch thread1Locked = new CountDownLatch(1);
        CountDownLatch thread1Done = new CountDownLatch(1);
        AtomicReference<Throwable> thread2Error = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            try (AutoCloseableLock lock = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(5))) {
                thread1Locked.countDown();
                thread1Done.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Thread 1 failed: " + e.getMessage());
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                thread1Locked.await(5, TimeUnit.SECONDS);
                lockManager.acquireLock(repo1.toString(), Duration.ofMillis(300));
                fail("Thread 2 should have timed out");
            } catch (Throwable e) {
                thread2Error.set(e);
            }
        });

        t1.start();
        assertTrue(thread1Locked.await(5, TimeUnit.SECONDS));

        t2.start();
        t2.join(3000);

        thread1Done.countDown();
        t1.join(3000);

        assertNotNull(thread2Error.get());
        assertInstanceOf(GitRepositoryLockTimeoutException.class, thread2Error.get());
    }

    @Test
    @DisplayName("Lock is cleanly released after normal try-with-resources completion")
    void lockReleaseAfterNormalCompletion() {
        assertFalse(lockManager.isLocked(repo1.toString()));

        try (AutoCloseableLock lock = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(2))) {
            assertTrue(lock.isValid());
            assertTrue(lockManager.isLockedByCurrentThread(repo1.toString()));
            assertTrue(lockManager.isLocked(repo1.toString()));
        }

        assertFalse(lockManager.isLockedByCurrentThread(repo1.toString()));
        assertFalse(lockManager.isLocked(repo1.toString()));

        // Subsequent acquisition immediately succeeds
        try (AutoCloseableLock lock2 = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(1))) {
            assertTrue(lock2.isValid());
        }
    }

    @Test
    @DisplayName("Lock is cleanly released when an exception is thrown inside try-with-resources")
    void lockReleaseAfterExceptions() {
        assertThrows(RuntimeException.class, () -> {
            try (AutoCloseableLock lock = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(2))) {
                assertTrue(lock.isValid());
                throw new RuntimeException("Simulated business exception");
            }
        });

        assertFalse(lockManager.isLockedByCurrentThread(repo1.toString()));
        assertFalse(lockManager.isLocked(repo1.toString()));

        // Subsequent acquisition succeeds without deadlock
        Optional<AutoCloseableLock> lock = lockManager.tryAcquireLock(repo1.toString());
        assertTrue(lock.isPresent());
        lock.get().close();
    }

    @Test
    @DisplayName("Invalid, blank, or non-existent repository paths fail with GitRepositoryLockInvalidPathException")
    void invalidRepositoryPathRejection() {
        assertThrows(GitRepositoryLockInvalidPathException.class, () ->
                lockManager.acquireLock(null)
        );

        assertThrows(GitRepositoryLockInvalidPathException.class, () ->
                lockManager.acquireLock("   ")
        );

        assertThrows(GitRepositoryLockInvalidPathException.class, () ->
                lockManager.acquireLock(tempDir.resolve("non_existent_folder").toString())
        );

        Path regularFile = tempDir.resolve("not_a_dir.txt");
        assertDoesNotThrow(() -> Files.writeString(regularFile, "hello"));
        assertThrows(GitRepositoryLockInvalidPathException.class, () ->
                lockManager.acquireLock(regularFile.toString())
        );
    }

    @Test
    @DisplayName("Two independent JVM processes contending for same lock: second process blocked until first releases")
    void twoIndependentJvmProcessesContendingForSameLock() throws Exception {
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                "com.traceback.core.remediation.GitRepositoryLockManagerTest$ExternalLockHolderProcess",
                lockDir.toString(),
                repo1.toString(),
                "2" // hold for 2 seconds
        );
        pb.redirectErrorStream(true);
        Process childProcess = pb.start();

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(childProcess.getInputStream()));
            String line = null;
            while ((line = reader.readLine()) != null) {
                if ("LOCKED".equals(line.trim())) {
                    break;
                }
            }
            assertEquals("LOCKED", line, "Child process should report LOCKED");

            // While child holds lock, parent non-blocking tryAcquireLock MUST return empty
            Optional<AutoCloseableLock> parentAttempt = lockManager.tryAcquireLock(repo1.toString());
            assertTrue(parentAttempt.isEmpty(), "Parent process must be locked out by external child process");

            // Wait for child process to finish its 2-second sleep and release
            int exitCode = childProcess.waitFor();
            assertEquals(0, exitCode, "Child process should exit cleanly");

            // Now parent should be able to acquire lock
            try (AutoCloseableLock parentLock = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(2))) {
                assertTrue(parentLock.isValid(), "Parent should acquire lock after child process releases");
            }
        } finally {
            if (childProcess.isAlive()) {
                childProcess.destroyForcibly();
            }
        }
    }

    @Test
    @DisplayName("Child process termination forcibly releases OS file lock, allowing recovery to proceed")
    void processTerminationReleasingOsLock() throws Exception {
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                "com.traceback.core.remediation.GitRepositoryLockManagerTest$ExternalLockHolderProcess",
                lockDir.toString(),
                repo1.toString(),
                "60" // hold for 60 seconds (simulating stalled process)
        );
        pb.redirectErrorStream(true);
        Process childProcess = pb.start();

        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(childProcess.getInputStream()));
            String line = null;
            while ((line = reader.readLine()) != null) {
                if ("LOCKED".equals(line.trim())) {
                    break;
                }
            }
            assertEquals("LOCKED", line, "Child process must report LOCKED");

            // Verify parent cannot acquire
            assertTrue(lockManager.tryAcquireLock(repo1.toString()).isEmpty());

            // SIMULATE ABRUPT CRASH: Kill child process forcibly (SIGKILL / terminateProcess)
            childProcess.destroyForcibly();
            assertTrue(childProcess.waitFor(5, TimeUnit.SECONDS), "Child process must terminate");

            // OS kernel automatically cleans up file descriptors upon process death.
            // Parent should now be able to acquire lock immediately!
            try (AutoCloseableLock parentLock = lockManager.acquireLock(repo1.toString(), Duration.ofSeconds(2))) {
                assertTrue(parentLock.isValid(), "Parent must acquire lock after external process crashed/terminated");
            }
        } finally {
            if (childProcess.isAlive()) {
                childProcess.destroyForcibly();
            }
        }
    }

    /**
     * Separate JVM process entry point for cross-process locking tests.
     */
    public static class ExternalLockHolderProcess {
        public static void main(String[] args) {
            try {
                Path lockDirectory = Path.of(args[0]);
                String repositoryPath = args[1];
                long holdSeconds = Long.parseLong(args[2]);

                DefaultGitRepositoryLockManager manager = new DefaultGitRepositoryLockManager(lockDirectory);
                try (AutoCloseableLock lock = manager.acquireLock(repositoryPath, Duration.ofSeconds(5))) {
                    System.out.println("LOCKED");
                    System.out.flush();
                    Thread.sleep(holdSeconds * 1000L);
                }
                System.out.println("RELEASED");
                System.out.flush();
                System.exit(0);
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                System.exit(1);
            }
        }
    }
}
