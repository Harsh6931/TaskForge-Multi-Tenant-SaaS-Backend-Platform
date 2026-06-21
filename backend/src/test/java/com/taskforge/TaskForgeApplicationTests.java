package com.taskforge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TaskForgeApplicationTests {

    @Test
    void contextLoads() {
        // Smoke test — verifies the Spring context starts without errors
    }
}
