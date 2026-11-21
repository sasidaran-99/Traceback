package com.traceback.core.repository;

import com.traceback.core.domain.RemediationTargetRefLockEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RemediationTargetRefLockRepository extends JpaRepository<RemediationTargetRefLockEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM RemediationTargetRefLockEntity l WHERE l.refName = :refName")
    Optional<RemediationTargetRefLockEntity> findByRefNameForUpdate(@Param("refName") String refName);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT l FROM RemediationTargetRefLockEntity l WHERE l.refName = :refName")
    Optional<RemediationTargetRefLockEntity> findByRefNameForShare(@Param("refName") String refName);
}
