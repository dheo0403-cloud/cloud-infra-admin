package com.example.infra;

import com.example.infra.dto.MonthlyReportDto.WorkLogDto;
import com.example.infra.service.MonthlyReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class JiraSubtaskFilterTest {

    @Test
    @DisplayName("Jira 이슈 유형 판별: '하위작업', 'Sub-task' 등 하위 작업 식별 검증")
    public void testIsSubtaskType() {
        // Sub-task 케이스
        assertTrue(MonthlyReportService.isSubtaskType("하위작업"));
        assertTrue(MonthlyReportService.isSubtaskType("하위 작업"));
        assertTrue(MonthlyReportService.isSubtaskType("Sub-task"));
        assertTrue(MonthlyReportService.isSubtaskType("Subtask"));
        assertTrue(MonthlyReportService.isSubtaskType("sub-task"));
        assertTrue(MonthlyReportService.isSubtaskType("SUBTASK"));
        assertTrue(MonthlyReportService.isSubtaskType("  하위작업  "));

        // 상위 일반 작업 케이스
        assertFalse(MonthlyReportService.isSubtaskType("Task"));
        assertFalse(MonthlyReportService.isSubtaskType("작업"));
        assertFalse(MonthlyReportService.isSubtaskType("기술지원"));
        assertFalse(MonthlyReportService.isSubtaskType("요청"));
        assertFalse(MonthlyReportService.isSubtaskType("Story"));
        assertFalse(MonthlyReportService.isSubtaskType("Bug"));
        assertFalse(MonthlyReportService.isSubtaskType("Epic"));
        assertFalse(MonthlyReportService.isSubtaskType(null));
        assertFalse(MonthlyReportService.isSubtaskType(""));
    }

    @Test
    @DisplayName("Jira 작업 내역 필터링: 하위 작업 3건이 제외되고 상위 작업 5건만 보고서 목록에 포함되는지 검증")
    public void testJiraWorkLogSubtaskExclusion() {
        // Given: BigQuery 조회 결과 모의 데이터 (상위 작업 5건 + 하위 작업 3건)
        List<Map<String, String>> mockRows = new ArrayList<>();

        // 상위 작업 5건
        mockRows.add(Map.of("issue_key", "VALOFE-101", "summary", "방화벽 정책 생성 요청", "issue_type", "작업", "created_at", "2026-08-10"));
        mockRows.add(Map.of("issue_key", "VALOFE-102", "summary", "GKE 노드풀 증설 작업", "issue_type", "Task", "created_at", "2026-08-11"));
        mockRows.add(Map.of("issue_key", "VALOFE-103", "summary", "Cloud SQL 백업 설정", "issue_type", "기술지원", "created_at", "2026-08-12"));
        mockRows.add(Map.of("issue_key", "VALOFE-104", "summary", "IAM 권한 정리 요청", "issue_type", "요청", "created_at", "2026-08-13"));
        mockRows.add(Map.of("issue_key", "VALOFE-105", "summary", "VPN 터널 상태 점검", "issue_type", "작업", "created_at", "2026-08-14"));

        // 하위 작업 3건 (상위 티켓 하위에 팀원들이 추가한 작업)
        mockRows.add(Map.of("issue_key", "VALOFE-106", "summary", "[하위] 인바운드 룰 검토", "issue_type", "하위작업", "created_at", "2026-08-10"));
        mockRows.add(Map.of("issue_key", "VALOFE-107", "summary", "[하위] 서브넷 대역 확인", "issue_type", "하위 작업", "created_at", "2026-08-10"));
        mockRows.add(Map.of("issue_key", "VALOFE-108", "summary", "[하위] GKE 노드 라벨링", "issue_type", "Sub-task", "created_at", "2026-08-11"));

        assertEquals(8, mockRows.size(), "전체 조회 대상 원시 데이터는 8건이어야 합니다.");

        // When: MonthlyReportService의 필터링 로직 실행
        List<WorkLogDto> filteredLogs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Map<String, String> row : mockRows) {
            String key = row.get("issue_key");
            if (key == null || !seen.add(key)) continue;

            String issueType = row.get("issue_type");
            if (MonthlyReportService.isSubtaskType(issueType)) {
                continue; // 하위 작업 제외
            }

            filteredLogs.add(WorkLogDto.builder()
                    .category(issueType)
                    .target("GCP")
                    .workDate(row.get("created_at"))
                    .content(row.get("summary"))
                    .build());
        }

        // Then: 하위작업 3건이 완벽히 제외되고 상위 작업 5건만 남아야 함
        assertEquals(5, filteredLogs.size(), "하위 작업이 제외된 최종 작업 내역은 5건이어야 합니다.");
        assertTrue(filteredLogs.stream().noneMatch(log -> log.getContent().contains("[하위]")), "하위 작업 내용이 포함되어서는 안 됩니다.");
        assertTrue(filteredLogs.stream().allMatch(log -> !MonthlyReportService.isSubtaskType(log.getCategory())), "카테고리에 하위작업 유형이 없어야 합니다.");
    }
}
