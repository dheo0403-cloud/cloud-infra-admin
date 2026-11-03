package com.example.infra.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MARK type 헤더(Slack 알람 종류) 매핑 검증
 */
class AlertRelayServiceTest {

    @Test
    void 알람_종류별_MARK_type() {
        assertEquals("azure_monitor", AlertRelayService.markType("Platform"));
        assertEquals("azure_monitor", AlertRelayService.markType("Log Alerts V2"));
        assertEquals("azure_monitor", AlertRelayService.markType("Activity Log - Administrative"));
        assertEquals("azure_resource_health", AlertRelayService.markType("Resource Health"));
        assertEquals("azure_service_health", AlertRelayService.markType("ServiceHealth"));
    }

    @Test
    void MARK_성공_판정은_본문_code까지_확인() {
        assertEquals(false, AlertRelayService.markAccepted(200, "{\"code\": 500, \"message\": \"type value is Not Found or Invalid - ERROR\"}"));
        assertEquals(true, AlertRelayService.markAccepted(200, "{\"code\": 200, \"message\": \"ok\"}"));
        assertEquals(true, AlertRelayService.markAccepted(200, "OK"));
        assertEquals(false, AlertRelayService.markAccepted(500, "{\"code\": 200}"));
    }
}
