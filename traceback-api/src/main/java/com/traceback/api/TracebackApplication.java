package com.traceback.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Traceback platform application entry point.
 */
@SpringBootApplication(scanBasePackages = "com.traceback")
@EntityScan(basePackages = "com.traceback.core.domain")
@EnableJpaRepositories(basePackages = "com.traceback.core.repository")
@EnableScheduling
public class TracebackApplication {

    public static void main(String[] args) {
        SpringApplication.run(TracebackApplication.class, args);
    }
}
