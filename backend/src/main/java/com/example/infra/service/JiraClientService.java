package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class JiraClientService {

    @Value("${jira.base-url:https://mzdevs.atlassian.net}")
    private String jiraBaseUrl;

    @Value("${jira.user-email:michael@mz.co.kr}")
    private String userEmail;

    @Value("${jira.api-token:}")
    private String apiToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String FIELDS_PARAM = "summary,status,created,updated,resolutiondate,assignee,reporter,issuetype,priority,labels,customfield_13299,customfield_13300,customfield_13301,customfield_14833,customfield_12757,customfield_14536,customfield_12842,customfield_16190";


    /**
     * 프로젝트 키 기준 전체 이슈 페이징 수집 (/rest/api/3/search/jql 사용)
     */
    public List<Map<String, Object>> fetchAllIssuesByProject(String projectKey) {
        String jql = String.format("project = '%s' ORDER BY created DESC", projectKey);
        return fetchIssuesWithJql(projectKey, jql);
    }

    /**
     * 프로젝트 키 및 최근 N일 기준 이슈 수집
     */
    public List<Map<String, Object>> fetchIssuesByProjectAndDays(String projectKey, Integer days) {
        String jql;
        if (days != null && days > 0) {
            jql = String.format("project = '%s' AND (created >= -%dd OR updated >= -%dd) ORDER BY created DESC", projectKey, days, days);
        } else {
            jql = String.format("project = '%s' ORDER BY created DESC", projectKey);
        }
        return fetchIssuesWithJql(projectKey, jql);
    }

    /**
     * 프로젝트 키 및 특정 기간(시작일 ~ 종료일 23:59) 기준 이슈 수집
     */
    public List<Map<String, Object>> fetchIssuesByDateRange(String projectKey, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        String jql = String.format("project = '%s' AND created >= '%s' AND created <= '%s 23:59' ORDER BY created DESC",
                projectKey, startDate.toString(), endDate.toString());
        return fetchIssuesWithJql(projectKey, jql);
    }

    public List<Map<String, Object>> fetchIssuesWithJql(String projectKey, String jql) {
        List<Map<String, Object>> allIssues = new ArrayList<>();

        if (apiToken == null || apiToken.trim().isEmpty()) {
            log.warn("[JIRA-CLIENT] Jira API Token is empty. Skipping issue fetch for project: {}", projectKey);
            return allIssues;
        }

        // Basic Auth 헤더 생성 (Base64 email:token)
        String authStr = userEmail + ":" + apiToken;
        String base64Auth = Base64.getEncoder().encodeToString(authStr.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + base64Auth);
        headers.set("Accept", "application/json");
        headers.setContentType(MediaType.APPLICATION_JSON);

        log.info("[JIRA-CLIENT] Starting issue fetch for project: {} (JQL: {}) from {}", projectKey, jql, jiraBaseUrl);

        String nextPageToken = null;
        boolean isLast = false;
        int maxResults = 50;

        while (!isLast) {
            try {
                String encodedJql = URLEncoder.encode(jql, StandardCharsets.UTF_8.toString());
                StringBuilder urlBuilder = new StringBuilder();
                urlBuilder.append(jiraBaseUrl.replaceAll("/+$", ""))
                        .append("/rest/api/3/search/jql?jql=").append(encodedJql)
                        .append("&maxResults=").append(maxResults)
                        .append("&fields=").append(FIELDS_PARAM);

                if (nextPageToken != null && !nextPageToken.trim().isEmpty()) {
                    urlBuilder.append("&nextPageToken=").append(URLEncoder.encode(nextPageToken, StandardCharsets.UTF_8.toString()));
                }

                URI uri = URI.create(urlBuilder.toString());
                HttpEntity<String> entity = new HttpEntity<>(headers);
                ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    isLast = root.path("isLast").asBoolean(true);
                    nextPageToken = root.path("nextPageToken").asText(null);

                    JsonNode issuesNode = root.path("issues");
                    if (issuesNode.isArray()) {
                        for (JsonNode issueNode : issuesNode) {
                            Map<String, Object> parsed = parseJiraIssue(issueNode, projectKey);
                            allIssues.add(parsed);
                        }
                    }

                    log.info("[JIRA-CLIENT] Fetched total {} issues so far for project {} (isLast: {})",
                            allIssues.size(), projectKey, isLast);

                    if (nextPageToken == null || nextPageToken.trim().isEmpty()) {
                        break;
                    }
                } else {
                    log.error("[JIRA-CLIENT] Failed to fetch issues for project {}. Status: {}, Body: {}",
                            projectKey, response.getStatusCode(), response.getBody());
                    break;
                }
            } catch (Exception e) {
                log.error("[JIRA-CLIENT] Error fetching issues for project {}: {}", projectKey, e.getMessage(), e);
                break;
            }

        }

        // 날짜순(최신순) 정렬 보장
        allIssues.sort((a, b) -> {
            String ca = (String) a.getOrDefault("created_at", "");
            String cb = (String) b.getOrDefault("created_at", "");
            return cb.compareTo(ca);
        });

        log.info("[JIRA-CLIENT] Completed issue fetch for project: {}. Total: {}", projectKey, allIssues.size());
        return allIssues;
    }


    /**
     * Jira JSON 노드를 플랫한 Map 구조로 파싱
     */
    private Map<String, Object> parseJiraIssue(JsonNode issueNode, String projectKey) {
        Map<String, Object> row = new HashMap<>();
        JsonNode fields = issueNode.path("fields");

        String issueKey = issueNode.path("key").asText("");
        row.put("issue_id", issueNode.path("id").asText(""));
        row.put("issue_key", issueKey);
        row.put("project_key", projectKey);
        row.put("summary", fields.path("summary").asText(""));
        row.put("browse_url", jiraBaseUrl.replaceAll("/+$", "") + "/browse/" + issueKey);

        // 상태, 이슈 유형, 우선순위
        row.put("issue_type", fields.path("issuetype").path("name").asText("기타"));
        row.put("status_name", fields.path("status").path("name").asText("미지정"));
        row.put("status_category", fields.path("status").path("statusCategory").path("name").asText("미지정"));
        row.put("priority", fields.path("priority").path("name").asText("Normal"));

        // 담당자 & 보고자
        JsonNode assignee = fields.path("assignee");
        row.put("assignee_name", assignee.isMissingNode() || assignee.isNull() ? "미지정" : assignee.path("displayName").asText("미지정"));
        row.put("assignee_email", assignee.isMissingNode() || assignee.isNull() ? "" : assignee.path("emailAddress").asText(""));

        JsonNode reporter = fields.path("reporter");
        row.put("reporter_name", reporter.isMissingNode() || reporter.isNull() ? "미지정" : reporter.path("displayName").asText("미지정"));
        row.put("reporter_email", reporter.isMissingNode() || reporter.isNull() ? "" : reporter.path("emailAddress").asText(""));

        // 일시 정보
        row.put("created_at", formatIsoTimestamp(fields.path("created").asText(null)));
        row.put("updated_at", formatIsoTimestamp(fields.path("updated").asText(null)));
        row.put("resolution_date", formatIsoTimestamp(fields.path("resolutiondate").asText(null)));

        // 라벨 목록 (쉼표 구분)
        List<String> labels = new ArrayList<>();
        if (fields.path("labels").isArray()) {
            fields.path("labels").forEach(l -> labels.add(l.asText()));
        }
        row.put("labels", String.join(",", labels));

        // Custom Fields 추출
        String managedType = fields.path("customfield_13299").isNull() ? null : fields.path("customfield_13299").asText(null);
        String workType1 = fields.path("customfield_13300").isNull() ? null : fields.path("customfield_13300").asText(null);
        String workType2 = fields.path("customfield_13301").isNull() ? null : fields.path("customfield_13301").asText(null);
        String workType3 = fields.path("customfield_14833").isNull() ? null : fields.path("customfield_14833").asText(null);
        String customerInfo = fields.path("customfield_12757").isNull() ? null : fields.path("customfield_12757").asText(null);

        String customerService = fields.path("customfield_14536").isNull() ? null : fields.path("customfield_14536").asText(null);
        if (customerService == null || customerService.isEmpty()) {
            JsonNode csArr = fields.path("customfield_12842");
            if (csArr.isArray() && csArr.size() > 0) {
                List<String> objIds = new ArrayList<>();
                csArr.forEach(obj -> {
                    if (obj.has("objectId")) objIds.add(obj.get("objectId").asText());
                });
                customerService = String.join(",", objIds);
            }
        }

        JsonNode reportNode = fields.path("customfield_16190");
        String reportYn = reportNode.has("value") ? reportNode.path("value").asText(null) : (reportNode.isNull() || reportNode.isMissingNode() ? null : reportNode.asText(null));

        String workCategory = mapWorkCategory(workType1, workType2, workType3, fields.path("summary").asText(""));

        row.put("managed_type", managedType);
        row.put("work_type_1", workType1);
        row.put("work_type_2", workType2);
        row.put("work_type_3", workType3);
        row.put("customer_info", customerInfo);
        row.put("customer_service", customerService);
        row.put("work_category", workCategory);
        row.put("report_yn", reportYn);


        return row;
    }

    /**
     * 업무 구분 7대 카테고리 매핑 (변경관리, 보안관리, 백업관리, 문제관리, 장애관리, 최적화, ETC)
     */
    public static String mapWorkCategory(String wt1, String wt2, String wt3, String summary) {
        String combined = String.format("%s %s %s %s",
                wt1 != null ? wt1 : "",
                wt2 != null ? wt2 : "",
                wt3 != null ? wt3 : "",
                summary != null ? summary : "").toLowerCase();

        if (combined.contains("change") || combined.contains("변경")) {
            return "변경관리";
        } else if (combined.contains("security") || combined.contains("보안") || combined.contains("isms") || combined.contains("acm") || combined.contains("armor") || combined.contains("oidc")) {
            return "보안관리";
        } else if (combined.contains("backup") || combined.contains("백업")) {
            return "백업관리";
        } else if (combined.contains("problem") || combined.contains("문제")) {
            return "문제관리";
        } else if (combined.contains("incident") || combined.contains("장애")) {
            return "장애관리";
        } else if (combined.contains("optimi") || combined.contains("최적화") || combined.contains("rightsizing") || combined.contains("다운사이징")) {
            return "최적화";
        } else {
            return "ETC";
        }
    }

    private String formatIsoTimestamp(String raw) {
        if (raw == null || raw.trim().isEmpty() || "null".equalsIgnoreCase(raw)) return null;
        try {
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(raw,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
            return odt.toInstant().toString();
        } catch (Exception e) {
            try {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(raw);
                return odt.toInstant().toString();
            } catch (Exception ex) {
                return raw;
            }
        }
    }
}
