package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GcpRecommenderService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    @lombok.Builder
    public static class GcpRecommendation {
        private String recommenderId;
        private String location;
        private String description;
        private String targetResource;
        private String priority;
    }

    /**
     * GCP 리소스 URI에서 순수 리소스 식별자(인스턴스명, 서비스계정 이메일, 디스크명 등) 추출
     */
    public static String extractResourceName(String uri) {
        if (uri == null || uri.trim().isEmpty()) return "";
        String clean = uri.trim().replaceAll("/+$", "");

        if (clean.contains("/serviceAccounts/")) {
            return clean.substring(clean.lastIndexOf("/serviceAccounts/") + 17);
        }
        if (clean.contains("/instances/")) {
            return clean.substring(clean.lastIndexOf("/instances/") + 11);
        }
        if (clean.contains("/disks/")) {
            return clean.substring(clean.lastIndexOf("/disks/") + 7);
        }
        if (clean.contains("/addresses/")) {
            return clean.substring(clean.lastIndexOf("/addresses/") + 11);
        }
        if (clean.contains("/buckets/")) {
            return clean.substring(clean.lastIndexOf("/buckets/") + 9);
        }
        if (clean.contains("/clusters/")) {
            return clean.substring(clean.lastIndexOf("/clusters/") + 10);
        }
        if (clean.contains("/roles/")) {
            return clean.substring(clean.lastIndexOf("/roles/") + 7);
        }
        if (clean.contains("/images/")) {
            return clean.substring(clean.lastIndexOf("/images/") + 8);
        }
        if (clean.contains("/subnetworks/")) {
            return clean.substring(clean.lastIndexOf("/subnetworks/") + 13);
        }
        if (clean.contains("/networks/")) {
            return clean.substring(clean.lastIndexOf("/networks/") + 10);
        }
        if (clean.contains("/projects/")) {
            int idx = clean.lastIndexOf("/projects/");
            String after = clean.substring(idx + 10);
            if (!after.contains("/")) return after;
        }

        int lastSlash = clean.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < clean.length() - 1) {
            return clean.substring(lastSlash + 1);
        }
        return clean;
    }

    /**
     * 영문/한글 설명 텍스트에서 대상 리소스 식별자(인스턴스명, IP, 이메일 등) 추출 Fallback
     */
    public static String extractTargetFromDescription(String desc) {
        if (desc == null || desc.isEmpty()) return "";

        // 1. 이미 [대상: xxx] 태그가 포함된 경우
        java.util.regex.Matcher mTag = java.util.regex.Pattern.compile("\\[(?:대상|Target):\\s*([^\\],]+)\\]").matcher(desc);
        if (mTag.find()) {
            return mTag.group(1).trim();
        }

        // 2. 따옴표 안의 리소스명/IP/계정: '10.0.0.1' 또는 'sa-name@...'
        java.util.regex.Matcher mQuote = java.util.regex.Pattern.compile("['\"]([a-zA-Z0-9._-]+(?:@[a-zA-Z0-9._-]+)?)['\"]").matcher(desc);
        if (mQuote.find()) {
            return mQuote.group(1).trim();
        }

        // 3. Cloud SQL 인스턴스(inst-name)
        java.util.regex.Matcher mSql = java.util.regex.Pattern.compile("(?:Cloud SQL 인스턴스\\s*\\(|instance:\\s*)([a-zA-Z0-9._-]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(desc);
        if (mSql.find()) {
            return mSql.group(1).trim();
        }

        // 4. instance inst-name
        java.util.regex.Matcher mVm = java.util.regex.Pattern.compile("(?:instance|인스턴스)\\s+([a-zA-Z0-9._-]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(desc);
        if (mVm.find()) {
            String val = mVm.group(1).trim();
            if (!"type".equalsIgnoreCase(val) && !"스펙".equalsIgnoreCase(val) && !"설정".equalsIgnoreCase(val)) {
                return val;
            }
        }

        return "";
    }

    /**
     * 권고사항을 [우선순위] [대상: 리소스명] 메시지 형태로 표준 규격화
     */
    public static String formatRecommendationText(String priority, String targetResource, String koreanDesc) {
        String prioTag = (priority != null && !priority.isEmpty()) ? priority.toUpperCase() : "MEDIUM";
        String targetTag = (targetResource != null && !targetResource.trim().isEmpty()) ? targetResource.trim() : "";

        // 이미 [대상: ...] 이나 [PRIORITY] 가 붙어있는지 확인
        String cleanDesc = koreanDesc != null ? koreanDesc.trim() : "";
        if (cleanDesc.startsWith("[" + prioTag + "]")) {
            cleanDesc = cleanDesc.substring(prioTag.length() + 2).trim();
        }

        if (!targetTag.isEmpty()) {
            if (!cleanDesc.contains("[대상:")) {
                return String.format("[%s] [대상: %s] %s", prioTag, targetTag, cleanDesc);
            } else {
                return String.format("[%s] %s", prioTag, cleanDesc);
            }
        } else {
            return String.format("[%s] %s", prioTag, cleanDesc);
        }
    }

    /**
     * GCP Active Assist Recommender REST API를 직접 호출하여 해당 분야별 추천 내용(description), 대상 리소스 및 우선순위 파싱
     */
    public List<GcpRecommendation> fetchRealGcpRecommendations(GoogleCredentials credentials, String projectId, String category) {
        List<GcpRecommendation> recommendations = new ArrayList<>();
        if (credentials == null || projectId == null || projectId.isEmpty()) return recommendations;

        List<String> recommenderIds = new ArrayList<>();
        if ("COST".equalsIgnoreCase(category)) {
            recommenderIds.add("google.compute.instance.MachineTypeRecommender");
            recommenderIds.add("google.compute.disk.IdleResourceRecommender");
            recommenderIds.add("google.compute.address.IdleResourceRecommender");
            recommenderIds.add("google.cloudsql.instance.OverprovisionedRecommender");
        } else if ("SECURITY".equalsIgnoreCase(category)) {
            recommenderIds.add("google.iam.policy.Recommender");
            recommenderIds.add("google.cloudsql.instance.SecurityRecommender");
            recommenderIds.add("google.gke.security.Recommender");
        } else if ("PERFORMANCE".equalsIgnoreCase(category)) {
            recommenderIds.add("google.compute.instance.PerformanceRecommender");
            recommenderIds.add("google.cloudsql.instance.PerformanceRecommender");
        } else if ("RELIABILITY".equalsIgnoreCase(category)) {
            recommenderIds.add("google.cloudsql.instance.ReliabilityRecommender");
            recommenderIds.add("google.compute.instance.ReliabilityRecommender");
        } else if ("MANAGABILITY".equalsIgnoreCase(category)) {
            recommenderIds.add("google.compute.instance.IdleResourceRecommender");
        } else if ("SUSTAINABILITY".equalsIgnoreCase(category)) {
            recommenderIds.add("google.compute.image.OldImageRecommender");
        }

        List<String> locations = List.of("global", "asia-northeast3", "asia-northeast1", "us-central1");

        try {
            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken() != null ? credentials.getAccessToken().getTokenValue() : null;
            if (accessToken == null) return recommendations;

            for (String recommenderId : recommenderIds) {
                for (String loc : locations) {
                    String apiUrl = String.format(
                            "https://recommender.googleapis.com/v1/projects/%s/locations/%s/recommenders/%s/recommendations",
                            projectId, loc, recommenderId
                    );

                    try {
                        URL url = new URL(apiUrl);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                        conn.setRequestProperty("Content-Type", "application/json");

                        int responseCode = conn.getResponseCode();
                        if (responseCode == 200) {
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                                StringBuilder response = new StringBuilder();
                                String line;
                                while ((line = br.readLine()) != null) {
                                    response.append(line);
                                }
                                JsonNode root = objectMapper.readTree(response.toString());
                                JsonNode recsNode = root.path("recommendations");
                                if (recsNode.isArray()) {
                                    for (JsonNode rec : recsNode) {
                                        String desc = rec.path("description").asText("");
                                        if (desc.isEmpty()) continue;

                                        // 1. Priority 추출 (P1=CRITICAL, P2=HIGH, P3=MEDIUM, P4=LOW)
                                        String rawPrio = rec.path("priority").asText("MEDIUM");
                                        String priority = "MEDIUM";
                                        if ("P1".equalsIgnoreCase(rawPrio) || "CRITICAL".equalsIgnoreCase(rawPrio)) {
                                            priority = "CRITICAL";
                                        } else if ("P2".equalsIgnoreCase(rawPrio) || "HIGH".equalsIgnoreCase(rawPrio)) {
                                            priority = "HIGH";
                                        } else if ("P3".equalsIgnoreCase(rawPrio) || "MEDIUM".equalsIgnoreCase(rawPrio)) {
                                            priority = "MEDIUM";
                                        } else if ("P4".equalsIgnoreCase(rawPrio) || "LOW".equalsIgnoreCase(rawPrio)) {
                                            priority = "LOW";
                                        }

                                        // 2. Target Resource 추출 (targetResources 배열 -> operations[].resource -> description fallback)
                                        String targetResource = "";
                                        JsonNode targetResArr = rec.path("targetResources");
                                        if (targetResArr.isArray() && targetResArr.size() > 0) {
                                            targetResource = extractResourceName(targetResArr.get(0).asText(""));
                                        }
                                        if (targetResource.isEmpty()) {
                                            JsonNode opGroups = rec.path("content").path("operationGroups");
                                            if (opGroups.isArray() && opGroups.size() > 0) {
                                                JsonNode ops = opGroups.get(0).path("operations");
                                                if (ops.isArray() && ops.size() > 0) {
                                                    targetResource = extractResourceName(ops.get(0).path("resource").asText(""));
                                                }
                                            }
                                        }
                                        if (targetResource.isEmpty()) {
                                            targetResource = extractTargetFromDescription(desc);
                                        }

                                        String koreanDesc = translateRecommendationToKorean(desc);
                                        String formatted = formatRecommendationText(priority, targetResource, koreanDesc);

                                        boolean exists = recommendations.stream().anyMatch(r -> r.getDescription().equals(formatted));
                                        if (!exists) {
                                            recommendations.add(new GcpRecommendation(recommenderId, loc, formatted, targetResource, priority));
                                        }
                                    }
                                }
                            }
                        } else {
                            log.debug("GCP Recommender API returned code {} for recommender {} at location {}", responseCode, recommenderId, loc);
                        }
                    } catch (Exception innerEx) {
                        log.debug("Failed location {} for recommender {}: {}", loc, recommenderId, innerEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch GCP Recommender API for project {}: {}", projectId, e.getMessage());
        }

        return recommendations;
    }

    public String translateRecommendationToKorean(String desc) {
        if (desc == null || desc.isEmpty()) return desc;

        if (desc.contains("Save cost by deleting idle IP address")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Save cost by deleting idle IP address ['\"]([^'\"]+)['\"]", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(desc);
            if (m.find()) {
                return String.format("미사용 유휴 고정 IP '%s'을(를) 삭제하여 비용을 절감하십시오.", m.group(1));
            }
            return "미사용 유휴 고정 IP를 삭제하여 비용을 절감하십시오.";
        }

        if (desc.contains("changing machine type from") || desc.contains("Change machine type from")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:changing|Change) machine type from\\s+([a-zA-Z0-9._-]+)\\s+to\\s+([a-zA-Z0-9._-]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(desc);
            if (m.find()) {
                return String.format("머신 유형을 %s에서 %s(으)로 축소(Rightsizing)하여 비용을 절감하십시오.", m.group(1), m.group(2));
            }
            return "VM 인스턴스의 머신 유형을 적정 사양으로 축소(Rightsizing)하여 비용을 절감하십시오.";
        }

        if (desc.contains("Save cost by deleting idle disk") || desc.contains("Delete idle disk")) {
            return "미사용 유휴 영구 디스크를 백업 후 삭제하여 스토리지 비용을 절감하십시오.";
        }

        if (desc.contains("Save cost by stopping idle VM") || desc.contains("Stop idle VM")) {
            return "사용되지 않는 유휴 VM 인스턴스를 중지하여 컴퓨팅 비용을 절감하십시오.";
        }

        if (desc.contains("table_open_cache")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("For your Cloud SQL instance:\\s*([^\\s,]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(desc);
            if (m.find()) {
                return String.format("Cloud SQL 인스턴스(%s): 리소스 사용량에 맞춰 table_open_cache 설정을 재구성하여 성능을 최적화하십시오.", m.group(1));
            }
            return "Cloud SQL 인스턴스: 리소스 사용량에 맞춰 table_open_cache 설정을 재구성하여 성능을 최적화하십시오.";
        }

        if (desc.contains("join_buffer_size")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("For your Cloud SQL instance:\\s*([^\\s,]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(desc);
            if (m.find()) {
                return String.format("Cloud SQL 인스턴스(%s): 쿼리 사용량에 맞춰 join_buffer_size 및 인덱스를 재구성하여 성능을 최적화하십시오.", m.group(1));
            }
            return "Cloud SQL 인스턴스: 쿼리 사용량에 맞춰 join_buffer_size 및 인덱스를 재구성하여 성능을 최적화하십시오.";
        }

        if (desc.contains("Reconfigure connections for this instance to optimize resource usage")) {
            return "리소스 사용량 최적화를 위해 인스턴스의 연결(Connection) 설정을 재구성하십시오.";
        }

        if (desc.contains("Configure your instance to increase backup retention")) {
            return "데이터 보호 및 복구 안정성을 위해 데이터베이스 백업 보존 기간을 연장하십시오.";
        }

        if (desc.contains("Configure your instance for high availability") || desc.contains("migrate to Enterprise Plus")) {
            return "서비스 가용성 향상을 위해 Cloud SQL 고가용성(HA)을 구성하거나 Enterprise Plus 에디션 적용을 권장합니다.";
        }

        if (desc.contains("This role has not been used during the observation window")) {
            return "관찰 기간 동안 사용되지 않은 IAM 역할입니다. 불필요한 과다 권한 회수를 권장합니다.";
        }

        if (desc.contains("Replace the current role with smaller predefined roles")) {
            return "최소 권한 원칙을 준수하기 위해 현재 역할을 필요한 권한만 포함하는 더 작은 사전 정의 역할들로 교체하십시오.";
        }

        if (desc.contains("Replace the current role with a smaller role")) {
            return "최소 권한 원칙을 준수하기 위해 현재 역할을 필요한 권한만 포함하는 더 작은 역할로 교체하십시오.";
        }

        if (desc.contains("Configure the instance to mandate SSL encryption")) {
            return "직접 연결 시 보안 강화를 위해 Cloud SQL 인스턴스에 SSL 암호화 연결을 필수로 구성하십시오.";
        }

        if (desc.contains("Enable database auditing")) {
            return "보안 및 규정 준수를 위해 데이터베이스 사용자 활동 감사(Auditing) 로깅을 활성화하십시오.";
        }

        if (desc.contains("Enable user password policies")) {
            return "비밀번호 만료 주기 및 연속 로그인 실패 시 계정 잠금 등 사용자 암호 보안 정책을 활성화하십시오.";
        }

        if (desc.contains("Enable instance password policies")) {
            return "기본 인증 사용자에 대해 강력한 비밀번호 생성을 강제하도록 인스턴스 암호 정책을 활성화하십시오.";
        }

        if (desc.contains("Replace OEV role with service agent role")) {
            return "OEV 역할을 서비스 에이전트 역할 및 세부 관리 역할로 교체하십시오.";
        }

        return desc;
    }

    public List<String> getSecurityRecommendations(Map<String, Integer> saSecurity, Map<String, Integer> bucketSecurity, int userPasswordOver90) {
        List<String> list = new ArrayList<>();
        int over90Keys = saSecurity != null ? saSecurity.getOrDefault("keyOver90", 0) : 0;
        int publicBuckets = bucketSecurity != null ? (bucketSecurity.getOrDefault("total", 0) - bucketSecurity.getOrDefault("notPublic", 0)) : 0;
        if (publicBuckets < 0) publicBuckets = 0;

        list.add(String.format("90일 이상 갱신되지 않은 Service Account Key(%d개)의 즉각적인 순환(Rotation) 적용을 권장합니다.", over90Keys));
        if (publicBuckets > 0) {
            list.add(String.format("인터넷에 노출된 GCS 공개 버킷(%d개)의 데이터 유출 방지를 위해 '공개 아님' 상태 변경을 강력히 권고합니다.", publicBuckets));
        } else {
            list.add("GCS 버킷의 Public Access Prevention(공개 액세스 차단) 설정 유지를 지속 점검하십시오.");
        }
        if (userPasswordOver90 > 0) {
            list.add(String.format("장기 비밀번호 미변경 사용자(%d명)에 대한 비밀번호 변경 안내 정책을 적용하십시오.", userPasswordOver90));
        } else {
            list.add("Cloud Identity IAM 사용자의 다중 요소 인증(MFA) 적용 강화를 권장합니다.");
        }
        return list;
    }

    public List<String> getCostRecommendations(int vmTotal, Map<String, Integer> storageSummary, Map<String, Integer> ipSummary) {
        List<String> list = new ArrayList<>();
        int idleDisks = storageSummary != null ? storageSummary.getOrDefault("diskIdle", 0) : 0;
        int unusedIps = ipSummary != null ? ipSummary.getOrDefault("externalUnused", 0) : 0;

        list.add("Active Assist 분석 결과, CPU 사용률이 10~15% 미만인 유휴 VM 인스턴스의 스펙 축소(Rightsizing)를 제안합니다.");
        if (idleDisks > 0) {
            list.add(String.format("인스턴스에 미연결된 미사용 영구 디스크(%d개)의 백업 후 삭제를 통한 과금 누수 방지가 필요합니다.", idleDisks));
        } else {
            list.add("사용되지 않는 영구 디스크 및 오래된 스냅샷의 데이터 수명주기(Lifecycle) 자동 삭제 적용을 권장합니다.");
        }
        if (unusedIps > 0) {
            list.add(String.format("미사용 상태로 유지 중인 VPC 고정 외부 IP(%d개)의 해제를 통한 비용 최적화를 권장합니다.", unusedIps));
        } else {
            list.add("CUD(지속 사용 약정) 커버리지율을 정기적으로 점검하여 추가 할인 혜택 저변 확대를 제안합니다.");
        }
        return list;
    }

    public List<String> getPerformanceRecommendations(Map<String, Integer> sqlHaTypes, Map<String, Integer> lbSummary) {
        List<String> list = new ArrayList<>();
        int singleSql = sqlHaTypes != null ? sqlHaTypes.getOrDefault("Zonal (Single)", 0) : 0;

        list.add("GKE 파드 재시작률(CrashLoopBackOff) 제어를 위한 오토스케일러(HPA/VPA) 점검 및 리소스 Limit 수정을 제안합니다.");
        if (singleSql > 0) {
            list.add(String.format("Cloud SQL 고가용성(HA) 미적용 인스턴스(%d개)에 대한 Regional HA 구성 적용을 권장합니다.", singleSql));
        } else {
            list.add("Cloud SQL 데이터베이스 슬로우 쿼리 모니터링 및 최신 엔진 버전 패치 적용을 권장합니다.");
        }
        list.add("VPC Load Balancer 헬스체크 응답 지연 및 백엔드 인스턴스 그룹 모니터링 강화를 제안합니다.");
        return list;
    }
}
