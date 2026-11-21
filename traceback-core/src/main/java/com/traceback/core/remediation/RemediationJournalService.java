package com.traceback.core.remediation;

import com.traceback.core.domain.RemediationOperationEventEntity;
import com.traceback.core.repository.RemediationOperationEventRepository;
import com.traceback.core.security.SecretRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the append-only remediation operation audit journal.
 */
@Service
public class RemediationJournalService {

    private static final Logger log = LoggerFactory.getLogger(RemediationJournalService.class);

    private final RemediationOperationEventRepository eventRepository;
    private final SecretRedactor secretRedactor;

    public RemediationJournalService(RemediationOperationEventRepository eventRepository, SecretRedactor secretRedactor) {
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository cannot be null");
        this.secretRedactor = Objects.requireNonNull(secretRedactor, "secretRedactor cannot be null");
    }

    /**
     * Appends an immutable audit event to the operation journal.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RemediationOperationEventEntity appendEvent(
            UUID operationId,
            Long fencingToken,
            RemediationOperationEventType eventType,
            RemediationOperationStatus fromStatus,
            RemediationOperationStatus toStatus,
            String refName,
            String expectedOldSha,
            String resultSha,
            String jgitResult,
            String details
    ) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(fencingToken, "fencingToken cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");

        String safeDetails = details != null ? secretRedactor.redact(details) : null;

        RemediationOperationEventEntity event = new RemediationOperationEventEntity();
        event.setOperationId(operationId);
        event.setFencingToken(fencingToken);
        event.setEventType(eventType);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setRefName(refName);
        event.setExpectedOldSha(expectedOldSha);
        event.setResultSha(resultSha);
        event.setJgitResult(jgitResult);
        event.setDetails(safeDetails);

        RemediationOperationEventEntity saved = eventRepository.save(event);
        log.debug("Journal event recorded: operationId={}, eventType={}, fencingToken={}", operationId, eventType, fencingToken);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RemediationOperationEventEntity> getEventsForOperation(UUID operationId) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        return eventRepository.findByOperationIdOrderByCreatedAtAsc(operationId);
    }

    @Transactional(readOnly = true)
    public boolean hasCommittedEvent(UUID operationId, RemediationOperationEventType eventType) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
        return eventRepository.existsByOperationIdAndEventType(operationId, eventType);
    }

    @Transactional(readOnly = true)
    public Optional<RemediationOperationEventEntity> getLatestEventOfType(UUID operationId, RemediationOperationEventType eventType) {
        Objects.requireNonNull(operationId, "operationId cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
        return eventRepository.findTopByOperationIdAndEventTypeOrderByCreatedAtDesc(operationId, eventType);
    }
}
