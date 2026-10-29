package com.example.infra.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MARK type 헤더(Slack 알람 종류) 매핑 검증
 */
class AlertRelayServiceTest {

    @Test
    void 알람_종류별_MARK_type() {
        assertEquals("Azure Monitor", AlertRelayService.markType("Platform"));
        assertEquals("Azure Monitor", AlertRelayService.markType("Log Alerts V2"));
        assertEquals("Azure Monitor", AlertRelayService.markType("Activity Log - Administrative"));
        assertEquals("Azure Resource Health", AlertRelayService.markType("Resource Health"));
        assertEquals("Azure Service Health", AlertRelayService.markType("ServiceHealth"));
    }
}
