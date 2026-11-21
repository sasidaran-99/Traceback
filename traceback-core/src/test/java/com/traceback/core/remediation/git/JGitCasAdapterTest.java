package com.traceback.core.remediation.git;

import com.traceback.core.remediation.RemediationFailureClassification;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JGitCasAdapterTest {

    @TempDir
    Path tempDir;

    private JGitCasAdapter adapter;
    private Git git;
    private ObjectId commitA;
    private ObjectId commitB;
    private ObjectId commitC;

    @BeforeEach
    void setUp() throws Exception {
        git = Git.init().setDirectory(tempDir.toFile()).call();
        adapter = new JGitCasAdapter(List.of(tempDir.toString()));

        // Create commit A
        File f1 = new File(tempDir.toFile(), "test.txt");
        Files.writeString(f1.toPath(), "initial content");
        git.add().addFilepattern("test.txt").call();
        commitA = git.commit().setMessage("Commit A").call().getId();

        // Create commit B (fast-forward child of A)
        Files.writeString(f1.toPath(), "second content");
        git.add().addFilepattern("test.txt").call();
        commitB = git.commit().setMessage("Commit B").call().getId();

        // Create commit C (divergent or further child)
        Files.writeString(f1.toPath(), "third content");
        git.add().addFilepattern("test.txt").call();
        commitC = git.commit().setMessage("Commit C").call().getId();
    }

    @Test
    @DisplayName("Path authorization validates allowed roots")
    void pathAuthorization() {
        assertTrue(adapter.isRepositoryAuthorized(tempDir.toString()));
        assertFalse(adapter.isRepositoryAuthorized("C:\\Windows\\System32"));
        assertFalse(adapter.isRepositoryAuthorized("/etc/shadow"));
    }

    @Test
    @DisplayName("Resolve ref SHA returns commit SHA for existing ref")
    void resolveRef() {
        Optional<String> sha = adapter.resolveRefSha(tempDir.toString(), "refs/heads/master");
        if (sha.isEmpty()) {
            sha = adapter.resolveRefSha(tempDir.toString(), "refs/heads/main");
        }
        assertTrue(sha.isPresent());
        assertEquals(commitC.name(), sha.get());
    }

    @Test
    @DisplayName("CAS update succeeds on valid expected old SHA")
    void casFastForwardSuccess() {
        String targetRef = "refs/heads/feature-cas";
        // Create ref pointing to commit A
        GitCasResult r1 = adapter.executeCompareAndSwap(
                tempDir.toString(),
                targetRef,
                ObjectId.zeroId().name(),
                commitA.name(),
                false
        );
        assertTrue(r1.success());
        assertEquals(commitA.name(), r1.resultingSha());
        assertEquals(RefUpdate.Result.NEW, r1.jgitResult());

        // Fast-forward to commit B
        GitCasResult r2 = adapter.executeCompareAndSwap(
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                false
        );
        assertTrue(r2.success());
        assertEquals(commitB.name(), r2.resultingSha());
        assertEquals(RefUpdate.Result.FAST_FORWARD, r2.jgitResult());
    }

    @Test
    @DisplayName("CAS update fails when expected old SHA does not match actual ref")
    void casMismatchFails() {
        String targetRef = "refs/heads/feature-mismatch";
        // Create ref pointing to commit B
        adapter.executeCompareAndSwap(
                tempDir.toString(),
                targetRef,
                ObjectId.zeroId().name(),
                commitB.name(),
                false
        );

        // Attempt CAS with wrong expected SHA (commitA instead of commitB)
        GitCasResult result = adapter.executeCompareAndSwap(
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitC.name(),
                false
        );

        assertFalse(result.success());
        assertEquals(RemediationFailureClassification.GIT_CAS_MISMATCH, result.failureClassification());
        assertNotNull(result.failureMessage());
    }

    @Test
    @DisplayName("Path traversal ref names are rejected and create no files outside .git")
    void pathTraversalRejected() {
        String[] dangerousRefs = {
                "../evil",
                "../../outside",
                "/absolute/path/evil",
                "refs/heads/../../evil",
                "refs/heads/../outside",
                "refs/heads/\\..\\escape"
        };

        for (String badRef : dangerousRefs) {
            GitCasResult result = adapter.executeCompareAndSwap(
                    tempDir.toString(),
                    badRef,
                    ObjectId.zeroId().name(),
                    commitA.name(),
                    false
            );

            assertFalse(result.success(), "Ref should be rejected: " + badRef);
            assertEquals(RemediationFailureClassification.VALIDATION_FAILURE, result.failureClassification());

            // Verify resolution also rejects
            Optional<String> resolved = adapter.resolveRefSha(tempDir.toString(), badRef);
            assertTrue(resolved.isEmpty(), "Dangerous ref resolution must be empty: " + badRef);
        }

        // Verify that NO files named 'evil' or 'outside' were written outside .git
        File evilInRoot = new File(tempDir.toFile(), "evil");
        assertFalse(evilInRoot.exists(), "No file should be created in repository root!");
        File outsideInParent = new File(tempDir.toFile().getParentFile(), "outside");
        assertFalse(outsideInParent.exists(), "No file should be created outside repository!");
    }

    @Test
    @DisplayName("Root-level symbolic refs like HEAD and unqualified names are rejected")
    void headAndUnqualifiedRefsRejected() {
        String[] rejected = {"HEAD", "FETCH_HEAD", "ORIG_HEAD", "MERGE_HEAD", "master", "main", "feature"};
        for (String ref : rejected) {
            GitCasResult result = adapter.executeCompareAndSwap(
                    tempDir.toString(),
                    ref,
                    commitA.name(),
                    commitB.name(),
                    false
            );
            assertFalse(result.success(), "Should reject: " + ref);
            assertEquals(RemediationFailureClassification.VALIDATION_FAILURE, result.failureClassification());
        }
    }

    @Test
    @DisplayName("Explicitly permitted namespaces refs/heads/ and refs/tags/ succeed")
    void validNamespacesSucceed() {
        // refs/heads/
        GitCasResult branchResult = adapter.executeCompareAndSwap(
                tempDir.toString(),
                "refs/heads/valid-branch",
                ObjectId.zeroId().name(),
                commitA.name(),
                false
        );
        assertTrue(branchResult.success());
        assertEquals(commitA.name(), branchResult.resultingSha());

        // refs/tags/
        GitCasResult tagResult = adapter.executeCompareAndSwap(
                tempDir.toString(),
                "refs/tags/valid-tag",
                ObjectId.zeroId().name(),
                commitB.name(),
                false
        );
        assertTrue(tagResult.success());
        assertEquals(commitB.name(), tagResult.resultingSha());
    }

    @Test
    @DisplayName("Malformed ref names are safely rejected without exposing internals")
    void malformedRefsRejected() {
        String[] malformed = {
                "refs/heads/.lock",
                "refs/heads/branch@{1}",
                "refs/heads/branch..double",
                "refs/heads/space in name",
                "refs/heads/branch//double-slash"
        };
        for (String ref : malformed) {
            GitCasResult result = adapter.executeCompareAndSwap(
                    tempDir.toString(),
                    ref,
                    ObjectId.zeroId().name(),
                    commitA.name(),
                    false
            );
            assertFalse(result.success(), "Malformed ref should be rejected: " + ref);
            assertEquals(RemediationFailureClassification.VALIDATION_FAILURE, result.failureClassification());
        }
    }

    @Test
    @DisplayName("CAS on deleted ref correctly classifies as GIT_CAS_MISMATCH, not lock failure")
    void deletedRefClassifiesAsMismatch() throws Exception {
        String targetRef = "refs/heads/to-be-deleted";
        // Create ref
        GitCasResult created = adapter.executeCompareAndSwap(
                tempDir.toString(),
                targetRef,
                ObjectId.zeroId().name(),
                commitA.name(),
                false
        );
        assertTrue(created.success());

        // Delete the branch via Git API
        git.branchDelete().setBranchNames(targetRef).call();

        // Attempt CAS expecting commitA
        GitCasResult result = adapter.executeCompareAndSwap(
                tempDir.toString(),
                targetRef,
                commitA.name(),
                commitB.name(),
                false
        );

        assertFalse(result.success());
        assertEquals(RemediationFailureClassification.GIT_CAS_MISMATCH, result.failureClassification());
        assertTrue(result.failureMessage().contains("does not exist or was deleted"));
    }
}
