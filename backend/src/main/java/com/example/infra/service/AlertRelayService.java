package com.example.infra.service;

import com.example.infra.entity.CloudProject;
import com.example.infra.entity.InfraEnvironment;
import com.example.infra.repository.InfraEnvironmentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 클라우드 알람 수신 → MARK 전송 → BigQuery alert_event 저장.
 * 저장은 추가만 한다 (스트리밍 버퍼 때문에 UPDATE·DELETE 하지 않음). 발생·해결 상태는 alert_id별 최신 행으로 판단.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertRelayService {

    private static final String TABLE = "alert_event";
    private static final long CUSTOMER_CACHE_MS = Duration.ofMinutes(10).toMillis();
    private static final long DEDUP_WINDOW_MS = Duration.ofMinutes(10).toMillis();

    private final BigQuery bigQuery;
    private final InfraEnvironmentRepository environmentRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String targetProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    @Value("${app.alert.mark.enabled:false}")
    private boolean markEnabled;

    @Value("${app.alert.mark.url:}")
    private String markUrl;

    @Value("${app.alert.mark.api-key:}")
    private String markApiKey;

    private volatile Map<String, String> customerById = Map.of();
    private volatile long customerLoadedAt = 0;
    private volatile boolean tableReady = false;
    // Azure 재전송(응답 지연·실패 시) 중복 차단: "alert_id|status" → 처리 시각 (운영 파드 1개 기준)
    private final Map<String, Long> recentlySent = new ConcurrentHashMap<>();

    /** 처리 결과(mark_status)를 반환한다. MARK 실패도 예외 없이 결과로 돌려준다 */
    public String relayAzure(JsonNode payload) {
        Optional<AzureAlertMapper.MarkAlert> mapped = AzureAlertMapper.map(payload);
        if (mapped.isEmpty()) {
            log.warn("Azure 알람 변환 불가 (공통 알람 형식 아님 또는 필수 값 없음), 원본만 저장");
            insert("AZURE", null, null, "UNPARSED", null, null, payload);
            return "UNPARSED";
        }
        AzureAlertMapper.MarkAlert alert = mapped.get();
        String customer = customerName(alert.serviceId());

        String markStatus;
        Integer httpCode = null;
        String error = null;
        if (customer == null) {
            markStatus = "UNMAPPED";
            log.warn("고객사 미등록 구독의 알람 (저장만): {} / {}", alert.serviceId(), alert.alertName());
        } else if (alert.skip()) {
            markStatus = "SKIPPED"; // 상태 변화 없는 리소스 헬스 이벤트 (저장만)
        } else if (!markEnabled) {
            markStatus = "MARK_DISABLED";
        } else if (isDuplicate(alert.alertId() + "|" + alert.status())) {
            markStatus = "DUPLICATE";
        } else {
            try {
                String type = markType(alert.signalType());
                HttpResponse<String> res = sendToMark(alert, customer, type);
                // MARK 명세에는 type=etc만 있어, 새 type이 4xx로 거부되면 etc로 한 번 더 보낸다 (누락 방지, 거부 기록은 남김)
                if (res.statusCode() / 100 == 4) {
                    error = truncate("type '" + type + "' 거부 HTTP " + res.statusCode() + " → etc로 재전송: " + res.body(), 1000);
                    log.warn("MARK {}", error);
                    res = sendToMark(alert, customer, "etc");
                }
                httpCode = res.statusCode();
                markStatus = httpCode / 100 == 2 ? "SENT" : "FAILED";
                if (!"SENT".equals(markStatus)) {
                    error = truncate((error == null ? "" : error + " / ") + res.body(), 1000);
                    log.error("MARK 전송 실패 HTTP {}: {} / {}", httpCode, alert.alertName(), error);
                }
            } catch (Exception e) {
                markStatus = "FAILED";
                error = truncate(e.toString(), 1000);
                log.error("MARK 전송 실패: {} / {}", alert.alertName(), error);
            }
            // 실패 건은 같은 알람이 다시 들어오면 재시도되도록 중복 기록에서 뺀다
            if ("FAILED".equals(markStatus)) recentlySent.remove(alert.alertId() + "|" + alert.status());
        }
        insert("AZURE", alert, customer, markStatus, httpCode, error, payload);
        return markStatus;
    }

    /** MARK(Slack)에 표시되는 알람 종류 */
    static String markType(String signalType) {
        return switch (signalType) {
            case "Resource Health" -> "Azure Resource Health";
            case "ServiceHealth" -> "Azure Service Health";
            default -> "Azure Monitor"; // 메트릭, 커스텀 로그, 활동 로그 등
        };
    }

    private HttpResponse<String> sendToMark(AzureAlertMapper.MarkAlert a, String customer, String type) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_id", a.serviceId());
        body.put("customer_name", customer);
        body.put("alert_id", a.alertId());
        body.put("severity", a.severity());
        body.put("status", a.status());
        body.put("alert_name", a.alertName());
        body.put("service_name", a.serviceName());
        body.put("target_name", a.targetName());
        body.put("timestamp", a.timestamp());
        if (!a.description().isBlank()) body.put("description", a.description());
        if (!a.currentValue().isBlank()) body.put("current_value", a.currentValue());

        HttpRequest req = HttpRequest.newBuilder(URI.create(markUrl))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("x-api-key", markApiKey)
            .header("type", type)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private boolean isDuplicate(String key) {
        long now = System.currentTimeMillis();
        recentlySent.values().removeIf(t -> now - t > DEDUP_WINDOW_MS);
        return recentlySent.putIfAbsent(key, now) != null;
    }

    /** 구독 ID·프로젝트 ID(소문자) → 고객사 이름. 10분 캐시, 조회 실패 시 이전 캐시 유지 */
    private String customerName(String serviceId) {
        long now = System.currentTimeMillis();
        if (now - customerLoadedAt > CUSTOMER_CACHE_MS) {
            synchronized (this) {
                if (now - customerLoadedAt > CUSTOMER_CACHE_MS) {
                    try {
                        Map<String, String> map = new HashMap<>();
                        for (InfraEnvironment env : environmentRepository.findAll()) {
                            if (env.getCustomer() == null || env.getProjects() == null) continue;
                            for (CloudProject p : env.getProjects()) {
                                if (p.getProjectId() != null) map.put(p.getProjectId().toLowerCase(), env.getCustomer().getName());
                            }
                        }
                        // 빈 결과(조회 중단 등)로 캐시를 비우면 모든 알람이 UNMAPPED가 되므로 이전 캐시 유지
                        if (map.isEmpty()) {
                            log.warn("고객사 목록이 비어 있음 (이전 캐시 사용)");
                        } else {
                            customerById = map;
                            customerLoadedAt = now;
                        }
                    } catch (Exception e) {
                        log.error("고객사 목록 조회 실패 (이전 캐시 사용): {}", e.getMessage());
                    }
                }
            }
        }
        return customerById.get(serviceId.toLowerCase());
    }

    private void ensureTable() {
        if (tableReady) return;
        try {
            bigQuery.query(QueryJobConfiguration.newBuilder(String.format(
                "CREATE TABLE IF NOT EXISTS `%s.%s.%s` (" +
                "  received_at TIMESTAMP, provider STRING, signal_type STRING, service_id STRING, customer_name STRING," +
                "  alert_id STRING, severity STRING, status STRING, alert_name STRING, service_name STRING, target_name STRING," +
                "  alert_time TIMESTAMP, description STRING, current_value STRING," +
                "  mark_status STRING, mark_http_code INT64, mark_error STRING, raw_payload STRING" +
                ") PARTITION BY DATE(received_at)", targetProjectId, datasetName, TABLE)).build());
            tableReady = true;
        } catch (Exception e) {
            log.warn("{} table check failed (continuing): {}", TABLE, e.getMessage());
        }
    }

    private void insert(String provider, AzureAlertMapper.MarkAlert a, String customer, String markStatus,
                        Integer httpCode, String error, JsonNode payload) {
        ensureTable();
        Map<String, Object> row = new HashMap<>();
        row.put("received_at", Instant.now().toString());
        row.put("provider", provider);
        row.put("customer_name", customer);
        row.put("mark_status", markStatus);
        row.put("mark_http_code", httpCode);
        row.put("mark_error", error);
        row.put("raw_payload", payload == null ? null : payload.toString());
        if (a != null) {
            row.put("signal_type", a.signalType());
            row.put("service_id", a.serviceId());
            row.put("alert_id", a.alertId());
            row.put("severity", a.severity());
            row.put("status", a.status());
            row.put("alert_name", a.alertName());
            row.put("service_name", a.serviceName());
            row.put("target_name", a.targetName());
            row.put("alert_time", Instant.ofEpochMilli(a.timestamp()).toString());
            row.put("description", a.description());
            row.put("current_value", a.currentValue());
        }
        try {
            InsertAllResponse res = bigQuery.insertAll(InsertAllRequest.newBuilder(
                TableId.of(targetProjectId, datasetName, TABLE)).addRow(row).build());
            if (res.hasErrors()) log.error("BigQuery insert error for {}: {}", TABLE, res.getInsertErrors());
        } catch (Exception e) {
            log.error("BigQuery insert failed for {} ({} / {})", TABLE, markStatus, a == null ? "-" : a.alertName(), e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
