package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * GCP Cloud Monitoring 웹훅(스키마 1.2) → MARK 알람 필드 변환.
 * 메트릭, Uptime Check, 로그 기반 알림을 처리한다. 로그 기반 알림은 GCP가 복구 알림을 보내지 않으므로 open만 들어온다.
 * 필수 값(프로젝트 ID, 인시던트 ID, 상태)이 없거나 다른 형식이면 빈 값을 반환한다 (가짜 값으로 채우지 않음).
 */
public final class GcpAlertMapper {

    static final String MONITOR = "GCP Monitor";
    static final String RESOURCE_HEALTH = "GCP Resource Health";
    static final String SERVICE_HEALTH = "GCP Service Health";

    private GcpAlertMapper() {}

    public static Optional<AzureAlertMapper.MarkAlert> map(JsonNode payload) {
        JsonNode incident = payload == null ? null : payload.path("incident");
        if (incident == null || !incident.isObject()) return Optional.empty();

        JsonNode resource = incident.path("resource");
        JsonNode labels = resource.path("labels");
        String projectId = AzureAlertMapper.firstNonBlank(labels.path("project_id").asText(), incident.path("scoping_project_id").asText());
        String alertId = incident.path("incident_id").asText();
        String status = incident.path("state").asText();
        if (projectId.isBlank() || alertId.isBlank() || !("open".equals(status) || "closed".equals(status))) {
            return Optional.empty();
        }

        // 복구 알림은 ended_at, 발생 알림은 started_at (GCP는 초 단위)
        long seconds = "closed".equals(status) && incident.path("ended_at").canConvertToLong()
            ? incident.path("ended_at").asLong() : incident.path("started_at").asLong();
        long timestamp = seconds > 0 ? seconds * 1000 : System.currentTimeMillis();

        String summary = incident.path("summary").asText();
        String documentation = incident.path("documentation").path("content").asText();
        String description = (summary + "\n" + documentation).trim();

        return Optional.of(new AzureAlertMapper.MarkAlert(
            signalType(incident.path("policy_user_labels").path("mark_type").asText()),
            projectId.toLowerCase(), alertId, severity(incident.path("severity").asText()), status,
            AzureAlertMapper.firstNonBlank(incident.path("policy_name").asText(), incident.path("condition_name").asText()),
            AzureAlertMapper.firstNonBlank(incident.path("resource_type_display_name").asText(), resource.path("type").asText()),
            targetName(incident, labels, projectId),
            timestamp, AzureAlertMapper.truncate(description), incident.path("observed_value").asText(), false));
    }

    /** 정책 라벨 mark_type으로 종류를 나눈다. 라벨이 없으면 일반 모니터링 알람 */
    private static String signalType(String markType) {
        return switch (markType) {
            case "resource_health" -> RESOURCE_HEALTH;
            case "service_health" -> SERVICE_HEALTH;
            default -> MONITOR;
        };
    }

    /** MARK는 Disaster·Critical·High만 받는다. Critical 외(Error, Warning, 등급 없음)는 High */
    private static String severity(String gcpSeverity) {
        return "critical".equalsIgnoreCase(gcpSeverity) ? "Critical" : "High";
    }

    /** resource_name은 "... labels {...}" 형태라 쓰지 않고, 프로젝트 ID 외 첫 라벨 값(host, instance_id 등)을 쓴다 */
    private static String targetName(JsonNode incident, JsonNode labels, String projectId) {
        String display = incident.path("resource_display_name").asText();
        if (!display.isBlank()) return display;
        Iterator<Map.Entry<String, JsonNode>> it = labels.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> label = it.next();
            if (!"project_id".equals(label.getKey()) && !label.getValue().asText().isBlank()) return label.getValue().asText();
        }
        return projectId;
    }
}
