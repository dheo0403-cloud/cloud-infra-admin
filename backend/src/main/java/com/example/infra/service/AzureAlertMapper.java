package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure Monitor 공통 알람 형식(Common Alert Schema) → MARK 알람 필드 변환.
 * 메트릭(Platform), 커스텀 로그(Log Alerts V2), 리소스 헬스, 서비스 헬스, 기타 활동 로그를 처리한다.
 * 공통 형식이 아니거나 구독 ID·알람 ID를 찾지 못하면 빈 값을 반환한다 (가짜 값으로 채우지 않음).
 */
public final class AzureAlertMapper {

    private static final Pattern SUBSCRIPTION = Pattern.compile("/subscriptions/([^/]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Set<String> SERVICE_HEALTH_CLOSED_STAGES = Set.of("Resolved", "RCA", "Complete");
    private static final int MAX_DESCRIPTION = 2000;

    private AzureAlertMapper() {}

    public record MarkAlert(String signalType, String serviceId, String alertId, String severity, String status,
                            String alertName, String serviceName, String targetName, long timestamp,
                            String description, String currentValue, boolean skip) {}

    public static Optional<MarkAlert> map(JsonNode payload) {
        if (payload == null || !"azureMonitorCommonAlertSchema".equals(payload.path("schemaId").asText())) {
            return Optional.empty();
        }
        JsonNode essentials = payload.path("data").path("essentials");
        JsonNode ctx = payload.path("data").path("alertContext");
        String monitoringService = essentials.path("monitoringService").asText();
        String alertRule = essentials.path("alertRule").asText();
        String targetId = essentials.path("alertTargetIDs").path(0).asText();

        String serviceId = subscriptionOf(targetId);
        if (serviceId == null) serviceId = subscriptionOf(essentials.path("alertId").asText());

        String severity;
        String status;
        String alertId;
        String alertName = alertRule;
        String serviceName = lastSegment(essentials.path("targetResourceType").asText());
        String targetName = firstNonBlank(essentials.path("configurationItems").path(0).asText(), lastSegment(targetId));
        // VMSS 인스턴스는 이름 칸에 번호(0, 1…)만 오므로 상위 리소스 이름을 붙인다 (예: aks-pool-vmss/0)
        String[] seg = targetId.split("/");
        if (targetName.matches("\\d+") && seg.length >= 3) targetName = seg[seg.length - 3] + "/" + targetName;
        boolean skip = false;
        String description = essentials.path("description").asText();
        String currentValue = ctx.path("condition").path("allOf").path(0).path("metricValue").asText();

        switch (monitoringService) {
            case "Resource Health" -> {
                // Azure 등급은 고정값이라 상태값으로 등급을 정하고, 같은 규칙·리소스는 같은 alert_id로 묶는다
                JsonNode p = ctx.path("properties");
                String health = p.path("currentHealthStatus").asText();
                String prev = p.path("previousHealthStatus").asText();
                // 장애 진행 중 "Updated" 이벤트도 Available/Available로 오므로, 실제 복구(Resolved 또는 비정상→Available)만 closed
                boolean recovered = "Resolved".equals(ctx.path("status").asText())
                    || ("Available".equals(health) && !prev.isBlank() && !"Available".equals(prev));
                severity = "Unavailable".equals(health) ? "Critical" : "High"; // Degraded·Unknown
                status = recovered ? "closed" : "open";
                // 상태 변화 없는 Available 이벤트는 가짜 복구가 되지 않게 MARK로 보내지 않는다
                skip = !recovered && "Available".equals(health);
                alertId = sha1(alertRule + "|" + targetId.toLowerCase());
                description = firstNonBlank(join(p.path("title").asText(), p.path("details").asText()), description);
                currentValue = health;
            }
            case "ServiceHealth" -> {
                // 장애 종류로 등급을 정하고, trackingId로 발생·해결을 묶는다
                JsonNode p = ctx.path("properties");
                severity = "Incident".equals(p.path("incidentType").asText()) ? "Critical" : "High";
                status = SERVICE_HEALTH_CLOSED_STAGES.contains(p.path("stage").asText()) ? "closed" : "open";
                alertId = p.path("trackingId").asText();
                alertName = firstNonBlank(p.path("title").asText(), alertRule);
                serviceName = firstNonBlank(p.path("service").asText(), serviceName);
                targetName = firstNonBlank(p.path("region").asText(), "Azure Service Health");
                description = firstNonBlank(stripHtml(p.path("communication").asText()), description);
                currentValue = "";
            }
            default -> {
                // 메트릭(Platform), 커스텀 로그(Log Alerts V2), 기타 활동 로그
                severity = switch (essentials.path("severity").asText()) {
                    case "Sev0" -> "Disaster";
                    case "Sev1" -> "Critical";
                    default -> "High"; // Sev2 및 미사용 Sev3·Sev4 (MARK는 3개 등급만 허용, 누락 방지)
                };
                status = "Resolved".equals(essentials.path("monitorCondition").asText()) ? "closed" : "open";
                alertId = essentials.path("alertId").asText();
                if (description.isBlank()) description = ctx.path("condition").path("allOf").path(0).path("searchQuery").asText();
            }
        }

        if (serviceId == null || alertId.isBlank()) return Optional.empty();

        String time = "closed".equals(status) ? essentials.path("resolvedDateTime").asText() : "";
        long timestamp = parseMillis(firstNonBlank(time, essentials.path("firedDateTime").asText()));

        return Optional.of(new MarkAlert(
            monitoringService, serviceId.toLowerCase(), alertId, severity, status,
            alertName, firstNonBlank(serviceName, monitoringService), firstNonBlank(targetName, serviceId),
            timestamp, truncate(description), currentValue, skip));
    }

    private static String subscriptionOf(String resourceId) {
        Matcher m = SUBSCRIPTION.matcher(resourceId == null ? "" : resourceId);
        return m.find() ? m.group(1) : null;
    }

    private static String lastSegment(String path) {
        if (path == null || path.isBlank()) return "";
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /** ISO 시각(오프셋 유무 모두) → epoch ms. 시각이 없거나 읽지 못하면 수신 시각을 쓴다 (원본은 raw_payload에 남음) */
    static long parseMillis(String iso) {
        if (iso == null || iso.isBlank()) return System.currentTimeMillis();
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (Exception ignored) {
                return System.currentTimeMillis();
            }
        }
    }

    private static String stripHtml(String html) {
        return HTML_TAG.matcher(html == null ? "" : html).replaceAll(" ").replaceAll("&nbsp;", " ")
            .replaceAll("\\s+", " ").trim();
    }

    private static String join(String a, String b) {
        return (a + " " + b).trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String truncate(String s) {
        return s.length() > MAX_DESCRIPTION ? s.substring(0, MAX_DESCRIPTION) : s;
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
