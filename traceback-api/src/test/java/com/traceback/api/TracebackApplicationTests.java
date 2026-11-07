package com.traceback.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TracebackApplicationTests {

    @Test
    @DisplayName("Should load Spring application context successfully")
    void contextLoads() {
    }
}
