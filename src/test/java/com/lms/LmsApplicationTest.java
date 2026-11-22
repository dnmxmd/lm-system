package com.lms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Catches wiring mistakes — a missing bean, a bad property — at build time rather than at deploy time. */
@SpringBootTest
@ActiveProfiles("test")
class LmsApplicationTest {

    @Test
    void the_application_context_starts() {
    }
}
