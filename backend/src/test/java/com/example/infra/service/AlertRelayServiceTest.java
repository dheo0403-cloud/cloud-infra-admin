package com.example.infra.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MARK type 헤더(Slack 알람 종류) 매핑과 서비스 헬스 번역 검증
 */
class AlertRelayServiceTest {

    private static AzureAlertMapper.MarkAlert alert(String signalType) {
        return new AzureAlertMapper.MarkAlert(signalType, "sub", "id", "High", "open",
            "English title", "svc", "target", 1L, "English description", "", false);
    }

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

    @Test
    void 서비스_헬스는_제목과_설명만_번역본으로_바꿈() {
        VertexAiGeminiService gemini = mock(VertexAiGeminiService.class);
        when(gemini.translateAlertToKorean(any(), any())).thenReturn(Map.of("title", "한글 제목", "description", "한글 설명"));
        var a = alert("ServiceHealth");
        var result = new AlertRelayService(null, null, gemini).translated(a);
        assertEquals("한글 제목", result.alertName());
        assertEquals("한글 설명", result.description());
        assertEquals(a.severity(), result.severity());
        assertEquals(a.alertId(), result.alertId());
    }

    @Test
    void 번역_실패나_예외면_원문으로_보냄() {
        VertexAiGeminiService gemini = mock(VertexAiGeminiService.class);
        when(gemini.translateAlertToKorean(any(), any())).thenReturn(null);
        var a = alert("ServiceHealth");
        assertSame(a, new AlertRelayService(null, null, gemini).translated(a));

        VertexAiGeminiService failing = mock(VertexAiGeminiService.class);
        when(failing.translateAlertToKorean(any(), any())).thenThrow(new RuntimeException("Vertex AI 오류"));
        assertSame(a, new AlertRelayService(null, null, failing).translated(a));
    }

    @Test
    void 서비스_헬스가_아니면_번역하지_않음() {
        VertexAiGeminiService gemini = mock(VertexAiGeminiService.class);
        var a = alert("Platform");
        assertSame(a, new AlertRelayService(null, null, gemini).translated(a));
        verifyNoInteractions(gemini);
    }
}
