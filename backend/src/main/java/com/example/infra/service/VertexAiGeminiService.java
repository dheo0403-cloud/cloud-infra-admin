package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class VertexAiGeminiService {

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String defaultProjectId;

    // Gemini 2.5 Flash 사용 설정: us-central1 우선 (검증 완료)
    private static final String PRIMARY_LOCATION = "us-central1";
    private static final String PRIMARY_MODEL = "gemini-2.5-flash";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GoogleCredentials credentials;

    // Connection Pool 및 Keep-Alive 재사용을 위한 싱글톤 HttpClient
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public VertexAiGeminiService() {
        try {
            InputStream is = getClass().getResourceAsStream("/gcp-credentials.json");
            if (is != null) {
                this.credentials = GoogleCredentials.fromStream(is)
                        .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
            } else {
                java.io.File file = new java.io.File("/home/azureadmin/csp-infra/gcp-credentials.json");
                if (file.exists()) {
                    try (InputStream fis = new java.io.FileInputStream(file)) {
                        this.credentials = GoogleCredentials.fromStream(fis)
                                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
                    }
                } else {
                    this.credentials = GoogleCredentials.getApplicationDefault()
                            .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
                }
            }
            log.info("[VERTEX-AI] GoogleCredentials successfully loaded. Model: {} / Location: {}", PRIMARY_MODEL, PRIMARY_LOCATION);
        } catch (Exception e) {
            log.warn("[VERTEX-AI] Failed to load GCP credentials: {}", e.getMessage());
        }
    }

    /**
     * [최적화 핵심] 3개 카테고리를 1번의 Vertex AI 호출로 통합 생성 (JSON 응답)
     * 소요시간 25초 -> 3~5초로 대폭 단축
     * 
     * @param categoryItems Map<카테고리명, 권고사항목록>
     * @return Map<카테고리명, 요약문>
     */
    public Map<String, String> summarizeAllCategoriesIntegrated(
            List<String> secRecs, List<String> costRecs, List<String> perfRecs) {
        
        long t0 = System.currentTimeMillis();
        Map<String, String> result = new LinkedHashMap<>();

        List<String> validSec = filterValidItems(secRecs);
        List<String> validCost = filterValidItems(costRecs);
        List<String> validPerf = filterValidItems(perfRecs);

        // 3개 카테고리 모두 권고가 없는 경우 즉시 기본 텍스트 반환 (0ms)
        if (validSec.isEmpty() && validCost.isEmpty() && validPerf.isEmpty()) {
            result.put("보안 및 컴플라이언스", getDefaultEmptySummary("보안"));
            result.put("비용 최적화", getDefaultEmptySummary("비용"));
            result.put("성능 및 안정성", getDefaultEmptySummary("성능"));
            return result;
        }

        // 1. 통합 프롬프트 생성 (카테고리별 상위 최대 3건만 추출하여 토큰 최소화)
        String prompt = buildIntegratedPrompt(validSec, validCost, validPerf);
        long promptTime = System.currentTimeMillis() - t0;
        log.info("[REPORT-PERF] ⑦ Executive Summary Prompt 생성 완료 ({}ms)", promptTime);

        // 2. Vertex AI 1회 통합 호출
        long aiCallStart = System.currentTimeMillis();
        String aiResponse = callGeminiApi(prompt);
        long aiDuration = System.currentTimeMillis() - aiCallStart;
        log.info("[REPORT-PERF] ⑨ Vertex AI 통합 1회 호출 완료 (소요시간: {}ms)", aiDuration);

        if (aiResponse != null && !aiResponse.trim().isEmpty()) {
            Map<String, String> parsed = parseIntegratedJsonResponse(aiResponse);
            if (parsed != null && !parsed.isEmpty()) {
                result.put("보안 및 컴플라이언스", parsed.getOrDefault("보안 및 컴플라이언스", getDefaultEmptySummary("보안")));
                result.put("비용 최적화", parsed.getOrDefault("비용 최적화", getDefaultEmptySummary("비용")));
                result.put("성능 및 안정성", parsed.getOrDefault("성능 및 안정성", getDefaultEmptySummary("성능")));
                log.info("[REPORT-PERF] ⑨-1 JSON 파싱 성공 (3개 카테고리 동시 생성 완료)");
                return result;
            }
        }

        // JSON 파싱 실패 시 병렬 비동기 fallback
        log.warn("[VERTEX-AI] Integrated JSON parsing failed, falling back to parallel async calls");
        return fallbackParallelSummarize(validSec, validCost, validPerf);
    }

    private List<String> filterValidItems(List<String> items) {
        if (items == null) return Collections.emptyList();
        return items.stream()
                .filter(s -> s != null && !s.trim().isEmpty()
                        && !s.contains("권고 사항 없음")
                        && !s.contains("없습니다")
                        && !s.contains("확인되지 않았습니다"))
                .limit(3) // 상위 중요 이슈 최대 3개만 유지 (토큰 및 지연시간 최적화)
                .toList();
    }

    private String buildIntegratedPrompt(List<String> sec, List<String> cost, List<String> perf) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 클라우드 MSP 기술 컨설턴트다.\n");
        sb.append("GCP Recommender 데이터를 경영진 보고용 Executive Summary로 작성한다.\n");
        sb.append("기술적인 세부 설명보다 핵심 이슈와 실행 가능한 개선 방향을 우선한다.\n");
        sb.append("각 카테고리는 최대 3~4문장으로만 작성하며, Recommendation 원문을 반복하거나 장문으로 재작성하지 않는다.\n\n");
        sb.append("[수집된 GCP Recommender 데이터]\n");

        sb.append("1. [보안 및 컴플라이언스]:\n");
        if (sec.isEmpty()) sb.append("  (권고사항 없음)\n");
        else for (String s : sec) sb.append("  - ").append(s).append("\n");

        sb.append("2. [비용 최적화]:\n");
        if (cost.isEmpty()) sb.append("  (권고사항 없음)\n");
        else for (String s : cost) sb.append("  - ").append(s).append("\n");

        sb.append("3. [성능 및 안정성]:\n");
        if (perf.isEmpty()) sb.append("  (권고사항 없음)\n");
        else for (String s : perf) sb.append("  - ").append(s).append("\n");

        sb.append("\n[작성 규칙]\n");
        sb.append("1. 각 카테고리는 반드시 다음 3~4문장 구조로 작성하세요:\n");
        sb.append("   - [1문장: 핵심 이슈] 중복을 통합하고 중요 이슈를 명확히 요약\n");
        sb.append("   - [2문장: 영향] 방치 시 발생하는 비즈니스/보안/비용/성능 영향을 1문장으로 작성\n");
        sb.append("   - [3~4문장: 개선 방향] 실행 가능한 구체적 조치를 1~2문장으로 작성\n");
        sb.append("2. 권고사항이 없는 카테고리는 '현재 [카테고리명] 대상 리소스가 확인되지 않았습니다. 추가 조치는 현재 필요하지 않습니다. 현재 상태를 유지하며 신규 리소스 생성 시 지속적으로 모니터링하세요.'로 작성하세요.\n");
        sb.append("3. 반드시 다음 JSON 형식으로만 응답하세요 (마크다운 코드블록이나 불필요한 텍스트 제외):\n");
        sb.append("{\n");
        sb.append("  \"security\": \"보안 요약 3~4문장\",\n");
        sb.append("  \"cost\": \"비용 요약 3~4문장\",\n");
        sb.append("  \"performance\": \"성능 요약 3~4문장\"\n");
        sb.append("}\n");

        return sb.toString();
    }

    private Map<String, String> parseIntegratedJsonResponse(String aiResponse) {
        try {
            String cleaned = aiResponse.trim();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.trim();

            JsonNode root = objectMapper.readTree(cleaned);
            Map<String, String> map = new LinkedHashMap<>();

            if (root.has("security") && !root.get("security").asText().isEmpty()) {
                map.put("보안 및 컴플라이언스", cleanAiResponse(root.get("security").asText()));
            }
            if (root.has("cost") && !root.get("cost").asText().isEmpty()) {
                map.put("비용 최적화", cleanAiResponse(root.get("cost").asText()));
            }
            if (root.has("performance") && !root.get("performance").asText().isEmpty()) {
                map.put("성능 및 안정성", cleanAiResponse(root.get("performance").asText()));
            }

            if (map.size() == 3) {
                return map;
            }
        } catch (Exception e) {
            log.warn("[VERTEX-AI] JSON parse exception: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, String> fallbackParallelSummarize(List<String> sec, List<String> cost, List<String> perf) {
        Map<String, String> map = new LinkedHashMap<>();
        CompletableFuture<String> secFuture = CompletableFuture.supplyAsync(() -> summarizeCategory("보안 및 컴플라이언스", sec, getDefaultEmptySummary("보안")));
        CompletableFuture<String> costFuture = CompletableFuture.supplyAsync(() -> summarizeCategory("비용 최적화", cost, getDefaultEmptySummary("비용")));
        CompletableFuture<String> perfFuture = CompletableFuture.supplyAsync(() -> summarizeCategory("성능 및 안정성", perf, getDefaultEmptySummary("성능")));

        try {
            CompletableFuture.allOf(secFuture, costFuture, perfFuture).join();
            map.put("보안 및 컴플라이언스", secFuture.get());
            map.put("비용 최적화", costFuture.get());
            map.put("성능 및 안정성", perfFuture.get());
        } catch (Exception e) {
            map.put("보안 및 컴플라이언스", getDefaultEmptySummary("보안"));
            map.put("비용 최적화", getDefaultEmptySummary("비용"));
            map.put("성능 및 안정성", getDefaultEmptySummary("성능"));
        }
        return map;
    }

    public String summarizeCategory(String categoryName, List<String> recommendations, String fallbackText) {
        if (recommendations == null || recommendations.isEmpty()) {
            return getDefaultEmptySummary(categoryName);
        }

        List<String> validItems = filterValidItems(recommendations);
        if (validItems.isEmpty()) {
            return getDefaultEmptySummary(categoryName);
        }

        String prompt = buildPrompt(categoryName, validItems);
        String aiResponse = callGeminiApi(prompt);

        if (aiResponse != null && !aiResponse.trim().isEmpty()) {
            return cleanAiResponse(aiResponse);
        }

        return fallbackText != null && !fallbackText.isEmpty() ? fallbackText : getDefaultEmptySummary(categoryName);
    }

    private String getDefaultEmptySummary(String categoryName) {
        if (categoryName.contains("보안")) {
            return "현재 보안 및 컴플라이언스 대상 리소스가 확인되지 않았습니다. 추가 보안 조치는 현재 필요하지 않습니다. 현재 상태를 유지하며 신규 리소스 생성 시 지속적으로 보안 권고를 확인하세요.";
        } else if (categoryName.contains("비용")) {
            return "현재 비용 최적화 대상 리소스가 확인되지 않았습니다. 추가 비용 절감 조치는 현재 필요하지 않습니다. 현재 상태를 유지하며 신규 리소스 생성 시 지속적으로 비용 최적화 권고를 확인하세요.";
        } else {
            return "현재 성능 및 안정성 대상 리소스가 확인되지 않았습니다. 추가 성능 최적화 조치는 현재 필요하지 않습니다. 현재 상태를 유지하며 신규 리소스 생성 시 지속적으로 성능 권고를 확인하세요.";
        }
    }

    private String buildPrompt(String categoryName, List<String> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 클라우드 MSP 기술 컨설턴트다.\n");
        sb.append("GCP Recommender 데이터를 경영진 보고용 Executive Summary로 작성한다.\n");
        sb.append("기술적인 세부 설명보다 핵심 이슈와 실행 가능한 개선 방향을 우선한다.\n");
        sb.append("각 카테고리는 최대 3~4문장으로만 작성하며, Recommendation 원문을 반복하거나 장문으로 재작성하지 않는다.\n\n");
        sb.append(String.format("다음은 GCP Recommender에서 수집된 [%s] 카테고리의 권고사항 목록입니다:\n\n", categoryName));
        for (String item : items) {
            sb.append(String.format("- %s\n", item));
        }
        sb.append("\n[출력 규칙 및 형식]\n");
        sb.append("1. 절대 하지 말 것:\n");
        sb.append("- Recommendation 원문을 그대로 붙여쓰기 금지\n");
        sb.append("- 동일한 내용이나 '데이터 무결성', '운영 리스크', '비즈니스 연속성' 등의 상투적 표현 반복 금지\n");
        sb.append("- 한 문단을 길게 나열하거나 5문장 이상 작성 금지\n\n");
        sb.append("2. 반드시 지킬 3~4문장 구조:\n");
        sb.append("- [1문장: 핵심 이슈] 여러 권고가 있어도 중복 내용을 통합하고 CRITICAL/HIGH 우선순위의 가장 중요한 이슈 최대 3개만 한 문장으로 요약\n");
        sb.append("- [2문장: 영향] 방치 시 발생하는 구체적인 비즈니스/운영/비용/보안 영향을 딱 한 문장으로 작성\n");
        sb.append("- [3~4문장: 개선 방향] 담당자가 즉시 실행 가능한 구체적 조치 방안을 1~2문장으로 작성\n\n");
        sb.append("3. 불필요한 마크다운 기호(*, #, -, 불릿 기호)나 라벨([핵심 이슈], [영향], [개선 방향] 등의 태그명) 없이 자연스럽게 읽히는 3~4문장의 완성된 줄글 문단으로 출력하세요.\n");
        return sb.toString();
    }

    /**
     * Vertex AI Gemini API 호출 (Java 11 HttpClient Connection Pool 재사용)
     */
    private String callGeminiApi(String promptText) {
        if (credentials == null) {
            log.warn("[VERTEX-AI] No credentials available, skipping AI call");
            return null;
        }

        List<String[]> candidates = List.of(
                new String[]{"us-central1", "gemini-2.5-flash"},
                new String[]{"us-central1", "gemini-1.5-flash"},
                new String[]{"asia-northeast3", "gemini-2.5-flash"}
        );

        for (String[] candidate : candidates) {
            String location = candidate[0];
            String model    = candidate[1];
            try {
                credentials.refreshIfExpired();
                String accessToken = credentials.getAccessToken().getTokenValue();

                String endpoint = String.format(
                        "https://%s-aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                        location, defaultProjectId, location, model);

                Map<String, Object> part = Map.of("text", promptText);
                Map<String, Object> content = Map.of("role", "user", "parts", List.of(part));
                Map<String, Object> genConfig = Map.of("temperature", 0.2, "maxOutputTokens", 2048);
                Map<String, Object> requestBody = Map.of(
                        "contents", List.of(content),
                        "generationConfig", genConfig
                );

                String jsonInput = objectMapper.writeValueAsString(requestBody);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonInput, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode candidates2 = root.path("candidates");
                    if (candidates2.isArray() && !candidates2.isEmpty()) {
                        JsonNode parts = candidates2.get(0).path("content").path("parts");
                        if (parts.isArray() && !parts.isEmpty()) {
                            String text = parts.get(0).path("text").asText();
                            if (text != null && !text.trim().isEmpty()) {
                                log.info("[VERTEX-AI] Success: model={} location={} project={}", model, location, defaultProjectId);
                                return text.trim();
                            }
                        }
                    }
                } else {
                    log.warn("[VERTEX-AI] HTTP {} for {}/{}: {}", response.statusCode(), location, model,
                            response.body().length() > 200 ? response.body().substring(0, 200) : response.body());
                }
            } catch (Exception e) {
                log.warn("[VERTEX-AI] Exception for {}/{}: {}", location, model, e.getMessage());
            }
        }

        log.warn("[VERTEX-AI] All candidates failed for project: {}", defaultProjectId);
        return null;
    }

    private String cleanAiResponse(String text) {
        if (text == null) return "";
        return text.replaceAll("[*#`]", "").replaceAll("\\s+", " ").trim();
    }
}
