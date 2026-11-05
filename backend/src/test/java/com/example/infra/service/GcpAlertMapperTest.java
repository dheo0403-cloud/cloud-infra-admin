package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05 mzc-gcp-managed 시험 정책에서 실제로 받은 웹훅 본문의 주요 필드로 검증한다
 */
class GcpAlertMapperTest {

    private static JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s);
    }

    private static String incident(String fields) {
        return "{\"version\":\"1.2\",\"incident\":{\"incident_id\":\"0.abc\",\"scoping_project_id\":\"scope-prj\"," + fields + "}}";
    }

    private static final String METRIC = """
        "policy_name":"test-metric","condition_name":"test-metric 조건","severity":"Critical",
        "policy_user_labels":{"mark_type":"monitor"},
        "resource":{"labels":{"project_id":"MZC-GCP-Managed"},"type":"consumed_api"},
        "resource_name":"mzc-gcp-managed Consumed API labels {project_id=mzc-gcp-managed}",
        "resource_type_display_name":"Consumed API","observed_value":"2.004",
        "documentation":{"content":"시험 정책","mime_type":"text/markdown"},
        "summary":"Request count is above the threshold of 0.000 with a value of 2.004."
        """;

    @Test
    void 메트릭_발생은_초를_밀리초로_바꾸고_필드를_채움() throws Exception {
        var a = GcpAlertMapper.map(json(incident(METRIC + ",\"started_at\":1793867427,\"ended_at\":null,\"state\":\"open\""))).orElseThrow();
        assertEquals("mzc-gcp-managed", a.serviceId());
        assertEquals("0.abc", a.alertId());
        assertEquals("Critical", a.severity());
        assertEquals("open", a.status());
        assertEquals("test-metric", a.alertName());
        assertEquals("Consumed API", a.serviceName());
        assertEquals("MZC-GCP-Managed", a.targetName()); // 표시용은 원래 표기 유지, service_id만 소문자
        assertEquals(1793867427000L, a.timestamp());
        assertEquals("2.004", a.currentValue());
        assertEquals("Request count is above the threshold of 0.000 with a value of 2.004.\n시험 정책", a.description());
        assertEquals(GcpAlertMapper.MONITOR, a.signalType());
    }

    @Test
    void 복구는_같은_인시던트_ID에_ended_at_시각() throws Exception {
        var a = GcpAlertMapper.map(json(incident(METRIC + ",\"started_at\":1793867427,\"ended_at\":1793867943,\"state\":\"closed\""))).orElseThrow();
        assertEquals("0.abc", a.alertId());
        assertEquals("closed", a.status());
        assertEquals(1793867943000L, a.timestamp());
    }

    @Test
    void Uptime은_프로젝트_ID_외_라벨을_대상으로_씀() throws Exception {
        var a = GcpAlertMapper.map(json(incident("""
            "policy_name":"test-uptime","severity":"Error","started_at":1793867469,"state":"open",
            "resource":{"labels":{"host":"portal.example.com","project_id":"p1"},"type":"uptime_url"},
            "resource_type_display_name":"Uptime Check URL","observed_value":"6.000"
            """))).orElseThrow();
        assertEquals("portal.example.com", a.targetName());
        assertEquals("High", a.severity());
    }

    @Test
    void 로그_알림은_측정값이_없고_등급은_정책을_따름() throws Exception {
        var a = GcpAlertMapper.map(json(incident("""
            "policy_name":"test-log","severity":"Warning","started_at":1793867565,"ended_at":null,"state":"open",
            "resource":{"labels":{"project_id":"p1"},"type":"global"},"resource_type_display_name":"Global",
            "summary":"Log match condition fired for Global with {project_id=p1}."
            """))).orElseThrow();
        assertEquals("High", a.severity());
        assertEquals("", a.currentValue());
        assertEquals("p1", a.targetName());
    }

    @Test
    void 등급이_없는_정책은_High() throws Exception {
        var a = GcpAlertMapper.map(json(incident("""
            "policy_name":"p","severity":"no severity","started_at":1,"state":"open","resource":{"labels":{"project_id":"p1"}}
            """))).orElseThrow();
        assertEquals("High", a.severity());
    }

    @Test
    void 정책_라벨로_종류를_나눔() throws Exception {
        String base = "\"policy_name\":\"p\",\"started_at\":1,\"state\":\"open\",\"resource\":{\"labels\":{\"project_id\":\"p1\"}},";
        assertEquals(GcpAlertMapper.RESOURCE_HEALTH, GcpAlertMapper.map(json(incident(base + "\"policy_user_labels\":{\"mark_type\":\"resource_health\"}"))).orElseThrow().signalType());
        assertEquals(GcpAlertMapper.SERVICE_HEALTH, GcpAlertMapper.map(json(incident(base + "\"policy_user_labels\":{\"mark_type\":\"service_health\"}"))).orElseThrow().signalType());
        assertEquals(GcpAlertMapper.MONITOR, GcpAlertMapper.map(json(incident(base + "\"policy_user_labels\":{}"))).orElseThrow().signalType());
    }

    @Test
    void 리소스에_프로젝트가_없으면_scoping_project_사용() throws Exception {
        var a = GcpAlertMapper.map(json(incident("\"policy_name\":\"p\",\"started_at\":1,\"state\":\"open\",\"resource\":{\"labels\":{}}"))).orElseThrow();
        assertEquals("scope-prj", a.serviceId());
    }

    @Test
    void 형식이_다르거나_필수값이_없으면_변환하지_않음() throws Exception {
        assertTrue(GcpAlertMapper.map(json("{}")).isEmpty());
        assertTrue(GcpAlertMapper.map(json("{\"schemaId\":\"azureMonitorCommonAlertSchema\"}")).isEmpty());
        assertTrue(GcpAlertMapper.map(json("{\"incident\":{\"state\":\"open\",\"scoping_project_id\":\"p\"}}")).isEmpty());
        assertTrue(GcpAlertMapper.map(json("{\"incident\":{\"incident_id\":\"1\",\"state\":\"open\"}}")).isEmpty());
        assertTrue(GcpAlertMapper.map(json(incident("\"state\":\"unknown\""))).isEmpty());
        assertFalse(GcpAlertMapper.map(json(incident("\"state\":\"open\""))).isEmpty());
    }

    @Test
    void 긴_설명은_2000자로_자름() throws Exception {
        var a = GcpAlertMapper.map(json(incident("\"state\":\"open\",\"summary\":\"" + "가".repeat(2500) + "\""))).orElseThrow();
        assertEquals(2000, a.description().length());
    }
}
