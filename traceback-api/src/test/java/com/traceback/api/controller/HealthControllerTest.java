package com.traceback.api.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/health should return UP status and service metadata")
    void shouldReturnHealthStatusUp() throws Exception {
        mockMvc.perform(get("/api/health")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("traceback-api"))
                .andExpect(jsonPath("$.version").value("1.0.0-SNAPSHOT"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/health/ready should return UP status when database is available")
    void shouldReturnReadinessUpWhenDatabaseIsAvailable() throws Exception {
        mockMvc.perform(get("/api/health/ready")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("traceback-api"))
                .andExpect(jsonPath("$.version").value("1.0.0-SNAPSHOT"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/health/readiness should return UP status via alternative readiness alias")
    void shouldReturnReadinessUpViaAlias() throws Exception {
        mockMvc.perform(get("/api/health/readiness")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /api/health/ready should return 503 DOWN when DataSource is null")
    void shouldReturnReadinessDownWhenDataSourceIsNull() throws Exception {
        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(new HealthController(null)).build();

        standaloneMvc.perform(get("/api/health/ready")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.details").value("Database unavailable"));
    }

    @Test
    @DisplayName("GET /api/health/ready should return 503 DOWN without leaking secrets when connection fails")
    void shouldReturnReadinessDownWhenConnectionThrowsWithoutLeakingSecrets() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenThrow(new SQLException("Fatal: password=secret123 connection failed to postgresql://admin@db:5432"));

        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(new HealthController(mockDs)).build();

        standaloneMvc.perform(get("/api/health/ready")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.details").value("Database unavailable"))
                .andExpect(jsonPath("$.details").value(not(containsString("secret123"))))
                .andExpect(jsonPath("$.details").value(not(containsString("password"))));
    }

    @Test
    @DisplayName("GET /api/health/ready should return 503 DOWN when connection is invalid")
    void shouldReturnReadinessDownWhenConnectionIsInvalid() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        Connection mockConn = mock(Connection.class);
        when(mockDs.getConnection()).thenReturn(mockConn);
        when(mockConn.isValid(2)).thenReturn(false);

        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(new HealthController(mockDs)).build();

        standaloneMvc.perform(get("/api/health/ready")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.details").value("Database unavailable"));
    }

    @Test
    @DisplayName("GET /api/health/ready should fail fast and return 503 DOWN when connection pool acquisition times out")
    void shouldReturnReadinessDownWhenConnectionAcquisitionTimesOut() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(invocation -> {
            // Simulate saturated pool blocking for 3 seconds
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                // Interrupted by cancel(true)
            }
            throw new SQLException("Connection pool exhausted");
        });

        // Set bounded timeout to 1 second
        HealthController controller = new HealthController(mockDs, 1);
        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(controller).build();

        long start = System.currentTimeMillis();
        standaloneMvc.perform(get("/api/health/ready")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.details").value("Database unavailable"));
        long elapsed = System.currentTimeMillis() - start;

        // Must fail fast within ~2.5 seconds (bounded by 1s timeout), far less than the 30s Hikari default
        org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(2500);

        controller.destroy();
    }

    @Test
    @DisplayName("GET /api/health/ready handles concurrent requests safely without failure")
    void shouldHandleConcurrentReadinessRequestsSafely() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(inv -> {
            Connection mockConn = mock(Connection.class);
            when(mockConn.isValid(anyInt())).thenReturn(true);
            return mockConn;
        });

        HealthController controller = new HealthController(mockDs, 2);
        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(controller).build();

        int concurrent = 4;
        java.util.concurrent.ExecutorService testExec = java.util.concurrent.Executors.newFixedThreadPool(concurrent);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(concurrent);
        java.util.List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < concurrent; i++) {
            futures.add(testExec.submit(() -> {
                latch.countDown();
                latch.await();
                return standaloneMvc.perform(get("/api/health/ready").accept(MediaType.APPLICATION_JSON))
                        .andReturn().getResponse().getStatus();
            }));
        }

        for (java.util.concurrent.Future<Integer> f : futures) {
            int status = f.get(5, java.util.concurrent.TimeUnit.SECONDS);
            // Must return 200 (healthy) or 503 (if rejected by bounded pool capacity), NEVER 500
            org.assertj.core.api.Assertions.assertThat(status).isIn(200, 503);
        }

        testExec.shutdown();
        controller.destroy();
    }

    @Test
    @DisplayName("GET /api/health/ready returns 503 DOWN when executor rejects task due to saturation")
    void shouldReturn503DownWhenExecutorRejectsTask() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        java.util.concurrent.ExecutorService rejectingExecutor = mock(java.util.concurrent.ExecutorService.class);
        when(rejectingExecutor.submit(any(java.util.concurrent.Callable.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("Task rejected: pool saturated"));

        HealthController controller = new HealthController(mockDs, 2, rejectingExecutor);
        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(controller).build();

        standaloneMvc.perform(get("/api/health/ready")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.details").value("Database unavailable"));
    }

    @Test
    @DisplayName("Repeated readiness requests during simulated DB unavailability do not spawn unbounded threads")
    void shouldNotSpawnUnboundedThreadsDuringRepeatedDbOutage() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        when(mockDs.getConnection()).thenAnswer(inv -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {}
            throw new SQLException("Database unreachable");
        });

        // 1-second readiness timeout, bounded pool (max 2 threads)
        HealthController controller = new HealthController(mockDs, 1);
        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // Count threads named traceback-readiness-check before
        java.util.Set<Thread> allThreads = Thread.getAllStackTraces().keySet();
        long initialReadinessThreads = allThreads.stream()
                .filter(t -> t.getName().contains("traceback-readiness-check") && t.isAlive())
                .count();

        // Fire 10 rapid readiness requests
        for (int i = 0; i < 10; i++) {
            standaloneMvc.perform(get("/api/health/ready")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("DOWN"));
        }

        // Active readiness check threads must never exceed the bounded maximum (<= 2)
        long currentReadinessThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().contains("traceback-readiness-check") && t.isAlive())
                .count();

        org.assertj.core.api.Assertions.assertThat(currentReadinessThreads - initialReadinessThreads).isLessThanOrEqualTo(2);

        controller.destroy();
    }

    @Test
    @DisplayName("GET /api/health/ready returns 503 DOWN cleanly after application shutdown")
    void shouldReturn503DownAfterShutdown() throws Exception {
        DataSource mockDs = mock(DataSource.class);
        HealthController controller = new HealthController(mockDs, 2);
        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // Shut down controller executor
        controller.destroy();

        // Subsequent readiness check must return 503 DOWN without throwing uncaught 500
        standaloneMvc.perform(get("/api/health/ready")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.details").value("Database unavailable"));
    }
}
