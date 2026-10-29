package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Azure 공통 알람 형식 샘플(Microsoft 문서 형식) 4종의 MARK 변환 검증
 */
class AzureAlertMapperTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String SUB = "11111111-2222-3333-4444-555555555555";
    private static final String VM_ID = "/subscriptions/" + SUB + "/resourceGroups/rg-prod/providers/Microsoft.Compute/virtualMachines/vm-web-01";

    private static JsonNode payload(String essentials, String alertContext) throws Exception {
        return OM.readTree("{\"schemaId\":\"azureMonitorCommonAlertSchema\",\"data\":{\"essentials\":" + essentials
            + ",\"alertContext\":" + alertContext + "}}");
    }

    private static String essentials(String monitoringService, String severity, String condition, String target,
                                     String configItem, String resourceType) {
        return "{\"alertId\":\"/subscriptions/" + SUB + "/providers/Microsoft.AlertsManagement/alerts/abc-123\","
            + "\"alertRule\":\"cpu-over-90\",\"severity\":\"" + severity + "\",\"signalType\":\"Metric\","
            + "\"monitorCondition\":\"" + condition + "\",\"monitoringService\":\"" + monitoringService + "\","
            + "\"alertTargetIDs\":[\"" + target + "\"],\"configurationItems\":[\"" + configItem + "\"],"
            + "\"targetResourceType\":\"" + resourceType + "\","
            + "\"firedDateTime\":\"2026-10-29T01:00:00.1234567Z\",\"resolvedDateTime\":\"2026-10-29T01:30:00Z\","
            + "\"description\":\"\"}";
    }

    @Test
    void 메트릭_발생과_해결() throws Exception {
        String ctx = "{\"condition\":{\"allOf\":[{\"metricName\":\"Percentage CPU\",\"metricValue\":95.2}]}}";
        var fired = AzureAlertMapper.map(payload(essentials("Platform", "Sev1", "Fired", VM_ID, "vm-web-01",
            "microsoft.compute/virtualmachines"), ctx)).orElseThrow();
        assertEquals(SUB, fired.serviceId());
        assertEquals("Critical", fired.severity());
        assertEquals("open", fired.status());
        assertEquals("cpu-over-90", fired.alertName());
        assertEquals("virtualmachines", fired.serviceName());
        assertEquals("vm-web-01", fired.targetName());
        assertEquals("95.2", fired.currentValue());
        assertEquals(Instant.parse("2026-10-29T01:00:00.123Z").toEpochMilli(), fired.timestamp());

        var resolved = AzureAlertMapper.map(payload(essentials("Platform", "Sev1", "Resolved", VM_ID, "vm-web-01",
            "microsoft.compute/virtualmachines"), ctx)).orElseThrow();
        assertEquals("closed", resolved.status());
        assertEquals(fired.alertId(), resolved.alertId());
        assertEquals(Instant.parse("2026-10-29T01:30:00Z").toEpochMilli(), resolved.timestamp());
    }

    @Test
    void 메트릭_등급_매핑() throws Exception {
        assertEquals("Disaster", AzureAlertMapper.map(payload(essentials("Platform", "Sev0", "Fired", VM_ID, "vm", "t"), "{}")).orElseThrow().severity());
        assertEquals("High", AzureAlertMapper.map(payload(essentials("Platform", "Sev2", "Fired", VM_ID, "vm", "t"), "{}")).orElseThrow().severity());
        assertEquals("High", AzureAlertMapper.map(payload(essentials("Platform", "Sev4", "Fired", VM_ID, "vm", "t"), "{}")).orElseThrow().severity());
    }

    @Test
    void 커스텀_로그_알람() throws Exception {
        String ws = "/subscriptions/" + SUB + "/resourceGroups/rg-log/providers/Microsoft.OperationalInsights/workspaces/law-prod";
        String ctx = "{\"conditionType\":\"LogQueryCriteria\",\"condition\":{\"allOf\":[{\"searchQuery\":\"AppExceptions | count\",\"metricValue\":12}]}}";
        var a = AzureAlertMapper.map(payload(essentials("Log Alerts V2", "Sev2", "Fired", ws, "law-prod",
            "microsoft.operationalinsights/workspaces"), ctx)).orElseThrow();
        assertEquals("High", a.severity());
        assertEquals("open", a.status());
        assertEquals("law-prod", a.targetName());
        assertEquals("12", a.currentValue());
        assertEquals("AppExceptions | count", a.description()); // 설명이 비면 쿼리를 넣는다
        assertEquals("Log Alerts V2", a.signalType());
    }

    @Test
    void 리소스_헬스_발생과_해결은_같은_alert_id() throws Exception {
        String ctx = "{\"properties\":{\"title\":\"가상 머신 사용 불가\",\"details\":\"플랫폼 문제\",\"currentHealthStatus\":\"%s\"}}";
        var down = AzureAlertMapper.map(payload(essentials("Resource Health", "Sev4", "Fired", VM_ID, "vm-web-01",
            "microsoft.compute/virtualmachines"), String.format(ctx, "Unavailable"))).orElseThrow();
        assertEquals("Critical", down.severity());
        assertEquals("open", down.status());
        assertEquals("Unavailable", down.currentValue());
        assertEquals("가상 머신 사용 불가 플랫폼 문제", down.description());

        // 해결 알림은 Azure에서 별도 알람(alertId 다름)으로 오지만 같은 alert_id로 묶여야 함
        String up = essentials("Resource Health", "Sev4", "Fired", VM_ID, "vm-web-01", "microsoft.compute/virtualmachines")
            .replace("alerts/abc-123", "alerts/zzz-999");
        var recovered = AzureAlertMapper.map(payload(up, String.format(ctx, "Available"))).orElseThrow();
        assertEquals("closed", recovered.status());
        assertEquals(down.alertId(), recovered.alertId());

        var degraded = AzureAlertMapper.map(payload(essentials("Resource Health", "Sev4", "Fired", VM_ID, "vm", "t"),
            String.format(ctx, "Degraded"))).orElseThrow();
        assertEquals("High", degraded.severity());
    }

    @Test
    void 서비스_헬스_장애종류와_stage() throws Exception {
        String subOnly = "/subscriptions/" + SUB;
        String ctx = "{\"properties\":{\"title\":\"Virtual Machines - Korea Central 연결 문제\",\"service\":\"Virtual Machines\","
            + "\"region\":\"Korea Central\",\"communication\":\"<p>현재 조사 중입니다.&nbsp;</p>\","
            + "\"incidentType\":\"%s\",\"trackingId\":\"ABC-1XY\",\"stage\":\"%s\"}}";
        var incident = AzureAlertMapper.map(payload(essentials("ServiceHealth", "Sev4", "Fired", subOnly, "", ""),
            String.format(ctx, "Incident", "Active"))).orElseThrow();
        assertEquals(SUB, incident.serviceId());
        assertEquals("Critical", incident.severity());
        assertEquals("open", incident.status());
        assertEquals("ABC-1XY", incident.alertId());
        assertEquals("Virtual Machines - Korea Central 연결 문제", incident.alertName());
        assertEquals("Virtual Machines", incident.serviceName());
        assertEquals("Korea Central", incident.targetName());
        assertEquals("현재 조사 중입니다.", incident.description());

        var resolved = AzureAlertMapper.map(payload(essentials("ServiceHealth", "Sev4", "Fired", subOnly, "", ""),
            String.format(ctx, "Incident", "Resolved"))).orElseThrow();
        assertEquals("closed", resolved.status());
        assertEquals("ABC-1XY", resolved.alertId());

        var maintenance = AzureAlertMapper.map(payload(essentials("ServiceHealth", "Sev4", "Fired", subOnly, "", ""),
            String.format(ctx, "Maintenance", "Planned"))).orElseThrow();
        assertEquals("High", maintenance.severity());
        assertEquals("open", maintenance.status());
    }

    @Test
    void 공통_형식이_아니면_변환하지_않음() throws Exception {
        assertTrue(AzureAlertMapper.map(OM.readTree("{\"schemaId\":\"AzureMonitorMetricAlert\",\"data\":{}}")).isEmpty());
        assertTrue(AzureAlertMapper.map(OM.readTree("{}")).isEmpty());
        // 구독 ID를 찾을 수 없으면 변환하지 않음
        String noSub = essentials("Platform", "Sev1", "Fired", "unknown", "vm", "t").replace("/subscriptions/" + SUB, "");
        assertTrue(AzureAlertMapper.map(payload(noSub, "{}")).isEmpty());
    }
}
