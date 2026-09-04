package com.example.infra;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

public class DateTimeParseTest {
    @Test
    public void testParse() {
        String expireTimeStr1 = "2026-09-09T16:59:59.000-07:00";
        try {
            Instant instant = Instant.parse(expireTimeStr1);
            System.out.println("Instant.parse succeeded: " + instant);
        } catch (Exception e) {
            System.out.println("Instant.parse failed: " + e.getMessage());
        }

        try {
            Instant instant = OffsetDateTime.parse(expireTimeStr1).toInstant();
            System.out.println("OffsetDateTime.parse succeeded: " + instant);
        } catch (Exception e) {
            System.out.println("OffsetDateTime.parse failed: " + e.getMessage());
        }
    }
}
