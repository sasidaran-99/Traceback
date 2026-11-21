package com.traceback.api.controller;

import com.traceback.api.dto.HealthResponse;
import com.traceback.core.security.SecretRedactor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Health check REST endpoint for system liveness and bounded database readiness monitoring.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);
    private static final int DEFAULT_READINESS_TIMEOUT_SECONDS = 3;
    private static final int DEFAULT_MAX_CONCURRENT_CHECKS = 2;

    private final DataSource dataSource;
    private final int readinessTimeoutSeconds;
    private final ExecutorService readinessExecutor;
    private final boolean ownsExecutor;

    @Autowired
    public HealthController(@Autowired(required = false) DataSource dataSource) {
        this(dataSource, DEFAULT_READINESS_TIMEOUT_SECONDS);
    }

    public HealthController(DataSource dataSource, int readinessTimeoutSeconds) {
        this(dataSource, readinessTimeoutSeconds, createBoundedReadinessExecutor(DEFAULT_MAX_CONCURRENT_CHECKS), true);
    }

    public HealthController(DataSource dataSource, int readinessTimeoutSeconds, ExecutorService readinessExecutor) {
        this(dataSource, readinessTimeoutSeconds, readinessExecutor, false);
    }

    private HealthController(DataSource dataSource, int readinessTimeoutSeconds, ExecutorService readinessExecutor, boolean ownsExecutor) {
        this.dataSource = dataSource;
        this.readinessTimeoutSeconds = Math.max(1, readinessTimeoutSeconds);
        this.readinessExecutor = readinessExecutor;
        this.ownsExecutor = ownsExecutor;
    }

    private static ThreadPoolExecutor createBoundedReadinessExecutor(int maxConcurrent) {
        AtomicInteger threadCounter = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "traceback-readiness-check-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                1,
                Math.max(1, maxConcurrent),
                30L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                factory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @PreDestroy
    public void destroy() {
        if (ownsExecutor && readinessExecutor != null) {
            readinessExecutor.shutdownNow();
        }
    }

    /**
     * Application liveness probe: returns 200 OK if the Spring Boot web application is running.
     */
    @GetMapping
    public ResponseEntity<HealthResponse> getHealth() {
        return ResponseEntity.ok(HealthResponse.up());
    }

    /**
     * Database readiness probe: returns 200 OK when database connectivity is validated,
     * or 503 Service Unavailable when the database is unavailable, acquisition times out,
     * or the readiness executor is saturated / rejecting tasks.
     * Enforces a bounded fail-fast timeout (<= 3s) without leaking secrets or spawning unbounded threads.
     */
    @GetMapping({ "/ready", "/readiness" })
    public ResponseEntity<HealthResponse> getReadiness() {
        if (dataSource == null) {
            log.warn("Readiness check failed: DataSource is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(HealthResponse.notReady("DOWN", "Database unavailable"));
        }

        Future<Boolean> checkFuture;
        try {
            checkFuture = readinessExecutor.submit(() -> {
                try (Connection conn = dataSource.getConnection()) {
                    return conn != null && conn.isValid(Math.min(readinessTimeoutSeconds, 2));
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Readiness check rejected: executor is saturated or shutting down");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(HealthResponse.notReady("DOWN", "Database unavailable"));
        }

        try {
            boolean isReady = checkFuture.get(readinessTimeoutSeconds, TimeUnit.SECONDS);
            if (isReady) {
                return ResponseEntity.ok(HealthResponse.ready("UP"));
            } else {
                log.warn("Readiness check failed: connection is invalid or null");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(HealthResponse.notReady("DOWN", "Database unavailable"));
            }
        } catch (TimeoutException e) {
            checkFuture.cancel(true);
            log.warn("Readiness check timed out after {}s waiting for database connection", readinessTimeoutSeconds);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(HealthResponse.notReady("DOWN", "Database unavailable"));
        } catch (InterruptedException e) {
            checkFuture.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("Readiness check interrupted");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(HealthResponse.notReady("DOWN", "Database unavailable"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Readiness check failed: {}", SecretRedactor.redactText(cause.getMessage()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(HealthResponse.notReady("DOWN", "Database unavailable"));
        }
    }
}
