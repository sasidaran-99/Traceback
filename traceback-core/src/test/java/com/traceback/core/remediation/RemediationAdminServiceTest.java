package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationTargetRefLockEntity;
import com.traceback.core.remediation.git.GitCasAdapter;
import com.traceback.core.repository.RemediationTargetRefLockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemediationAdminServiceTest {

    private RemediationTargetRefLockRepository targetRefLockRepository;
    private RemediationLockCoordinator lockCoordinator;
    private GitCasAdapter gitCasAdapter;
    private RemediationAdminService adminService;

    private Map<String, RemediationTargetRefLockEntity> locks;

    @BeforeEach
    void setUp() {
        locks = new HashMap<>();
        targetRefLockRepository = mock(RemediationTargetRefLockRepository.class);
        gitCasAdapter = mock(GitCasAdapter.class);

        when(targetRefLockRepository.findByRefNameForUpdate(any(String.class))).thenAnswer(inv -> {
            String ref = inv.getArgument(0);
            RemediationTargetRefLockEntity l = locks.computeIfAbsent(ref, RemediationTargetRefLockEntity::new);
            return Optional.of(l);
        });

        when(targetRefLockRepository.findById(any(String.class))).thenAnswer(inv -> {
            String ref = inv.getArgument(0);
            return Optional.ofNullable(locks.get(ref));
        });

        when(targetRefLockRepository.save(any(RemediationTargetRefLockEntity.class))).thenAnswer(inv -> {
            RemediationTargetRefLockEntity l = inv.getArgument(0);
            locks.put(l.getRefName(), l);
            return l;
        });

        when(gitCasAdapter.isValidTargetRef(any(String.class))).thenAnswer(inv -> {
            String ref = inv.getArgument(0);
            return ref != null && (ref.startsWith("refs/heads/") || ref.startsWith("refs/tags/")) && !ref.contains("..");
        });

        lockCoordinator = new RemediationLockCoordinator(targetRefLockRepository);
        adminService = new RemediationAdminService(lockCoordinator, gitCasAdapter, targetRefLockRepository);
    }

    @Test
    @DisplayName("Freezing a valid ref updates lock entity to frozen")
    void testFreezeValidRef() {
        String validRef = "refs/heads/release-1.0";
        adminService.freezeTargetRef(validRef, "Maintenance window");

        assertTrue(adminService.isRefFrozen(validRef));
        RemediationTargetRefLockEntity lock = locks.get(validRef);
        assertTrue(lock.getIsFrozen());
    }

    @Test
    @DisplayName("Unfreezing a frozen ref clears freeze status")
    void testUnfreezeValidRef() {
        String validRef = "refs/heads/release-1.0";
        adminService.freezeTargetRef(validRef, "Maintenance window");
        assertTrue(adminService.isRefFrozen(validRef));

        adminService.unfreezeTargetRef(validRef);
        assertFalse(adminService.isRefFrozen(validRef));
    }

    @Test
    @DisplayName("Freezing an invalid traversal ref throws IllegalArgumentException and rejects mutation")
    void testFreezeInvalidRefRejection() {
        assertThrows(IllegalArgumentException.class, () ->
                adminService.freezeTargetRef("../../etc/passwd", "Attack payload")
        );
        assertThrows(IllegalArgumentException.class, () ->
                adminService.freezeTargetRef("refs/heads/../../escape", "Attack payload")
        );
        assertThrows(IllegalArgumentException.class, () ->
                adminService.freezeTargetRef("HEAD", "Attack payload")
        );

        assertTrue(locks.isEmpty());
    }

    @Test
    @DisplayName("Unfreezing an invalid ref throws IllegalArgumentException")
    void testUnfreezeInvalidRefRejection() {
        assertThrows(IllegalArgumentException.class, () ->
                adminService.unfreezeTargetRef("../../etc/passwd")
        );
    }
}
