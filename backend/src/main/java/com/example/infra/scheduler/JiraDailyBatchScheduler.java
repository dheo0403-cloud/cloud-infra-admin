package com.example.infra.scheduler;

import com.example.infra.entity.InfraCustomer;
import com.example.infra.repository.InfraCustomerRepository;
import com.example.infra.service.JiraBigQueryService;
import com.example.infra.service.JiraClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JiraDailyBatchScheduler {

    private final InfraCustomerRepository customerRepository;
    private final JiraClientService jiraClientService;
    private final JiraBigQueryService jiraBigQueryService;

    /**
     * 매일 새벽 02:00 (KST) 등록된 고객사별 Jira 이슈 일 배치 수집
     * (배치가 새벽에 실행되므로 전일(D-1)까지의 최근 120일치 데이터를 수집하여 전일 스냅샷으로 적재)
     * 예: 8월 20일 새벽 2시 실행 시 -> 8월 19일까지의 최근 120일치 데이터 수집 및 snapshot_date = 2026-08-19 적재
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void runDailyJiraBatch() {
        LocalDate executionDate = LocalDate.now();
        LocalDate snapshotDate = executionDate.minusDays(1); // 배치 실행일 전일
        LocalDate startDate = snapshotDate.minusDays(120);    // 최근 120일 전

        log.info("[JIRA-BATCH] Starting daily Jira batch (Execution: {}, Range: {} ~ {}, Snapshot Date: {})",
                executionDate, startDate, snapshotDate, snapshotDate);
        int processed = syncAllCustomersWithDateRange(startDate, snapshotDate);
        log.info("[JIRA-BATCH] Completed daily Jira collection batch. Total processed: {}", processed);
    }

    /**
     * 수동 실행 또는 특정 기준일자 동기화 (기준일자 전일 기준 120일치 수집)
     */
    public int syncAllCustomers(LocalDate targetDate) {
        LocalDate snapshotDate = targetDate.minusDays(1);
        LocalDate startDate = snapshotDate.minusDays(120);
        return syncAllCustomersWithDateRange(startDate, snapshotDate);
    }

    /**
     * 특정 기간(startDate ~ endDate) 기준 전체 고객사 Jira 이슈 수집 및 BigQuery 적재 (최신 1벌 유지)
     */
    public int syncAllCustomersWithDateRange(LocalDate startDate, LocalDate endDate) {
        log.info("[JIRA-BATCH] Truncating table to maintain latest 1 set of 120-day issues...");
        jiraBigQueryService.truncateTable();

        List<InfraCustomer> customers = customerRepository.findAll();
        int totalProcessed = 0;


        for (InfraCustomer customer : customers) {
            if (Boolean.TRUE.equals(customer.getIsDeleted())) continue;

            // 1. GCP Jira 프로젝트 수집
            String gcpKey = customer.getJiraProjectKeyGcp();
            if (gcpKey != null && !gcpKey.trim().isEmpty()) {
                String trimmedKey = gcpKey.trim().toUpperCase();
                try {
                    log.info("[JIRA-BATCH] Processing customer GCP: {} (Jira Key: {}, Range: {} ~ {})",
                            customer.getName(), trimmedKey, startDate, endDate);
                    List<Map<String, Object>> issues = jiraClientService.fetchIssuesByDateRange(trimmedKey, startDate, endDate);
                    jiraBigQueryService.saveDailyJiraIssues(customer.getId(), customer.getName(), "GCP", trimmedKey, issues, endDate);
                    totalProcessed += issues.size();
                } catch (Exception e) {
                    log.error("[JIRA-BATCH] Failed to sync GCP Jira issues for customer {} (Key: {}): {}",
                            customer.getName(), trimmedKey, e.getMessage(), e);
                }
            }

            // 2. Azure Jira 프로젝트 수집
            String azKey = customer.getJiraProjectKeyAzure();
            if (azKey != null && !azKey.trim().isEmpty()) {
                String trimmedKey = azKey.trim().toUpperCase();
                try {
                    log.info("[JIRA-BATCH] Processing customer Azure: {} (Jira Key: {}, Range: {} ~ {})",
                            customer.getName(), trimmedKey, startDate, endDate);
                    List<Map<String, Object>> issues = jiraClientService.fetchIssuesByDateRange(trimmedKey, startDate, endDate);
                    jiraBigQueryService.saveDailyJiraIssues(customer.getId(), customer.getName(), "AZURE", trimmedKey, issues, endDate);
                    totalProcessed += issues.size();
                } catch (Exception e) {
                    log.error("[JIRA-BATCH] Failed to sync Azure Jira issues for customer {} (Key: {}): {}",
                            customer.getName(), trimmedKey, e.getMessage(), e);
                }
            }
        }
        return totalProcessed;
    }
}
