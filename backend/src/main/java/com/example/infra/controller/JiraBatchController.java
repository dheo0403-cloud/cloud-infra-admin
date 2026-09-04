package com.example.infra.controller;

import com.example.infra.entity.InfraCustomer;
import com.example.infra.repository.InfraCustomerRepository;
import com.example.infra.scheduler.JiraDailyBatchScheduler;
import com.example.infra.service.JiraBigQueryService;
import com.example.infra.service.JiraClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/jira")
@RequiredArgsConstructor
public class JiraBatchController {

    private final JiraDailyBatchScheduler batchScheduler;
    private final JiraClientService jiraClientService;
    private final JiraBigQueryService jiraBigQueryService;
    private final InfraCustomerRepository customerRepository;

    /**
     * 전체 등록 고객사 Jira 일괄 수동 동기화
     */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> triggerSyncAll() {
        LocalDate today = LocalDate.now();
        int count = batchScheduler.syncAllCustomers(today);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("message", "Jira sync completed successfully");
        res.put("date", today.toString());
        res.put("totalIssuesLoaded", count);
        return ResponseEntity.ok(res);
    }

    /**
     * 특정 프로젝트 키 기준 수동 동기화
     */
    @PostMapping("/sync-project")
    public ResponseEntity<Map<String, Object>> triggerSyncProject(
            @RequestParam String projectKey,
            @RequestParam(required = false, defaultValue = "GCP") String providerType) {
        String trimmed = projectKey.trim().toUpperCase();
        LocalDate today = LocalDate.now();

        // 등록된 고객사 매칭 탐색
        List<InfraCustomer> customers = customerRepository.findAll();
        String customerId = "";
        String customerName = "직접 동기화";
        String resolvedProvider = providerType.toUpperCase();

        for (InfraCustomer c : customers) {
            if (trimmed.equalsIgnoreCase(c.getJiraProjectKeyGcp())) {
                customerId = c.getId();
                customerName = c.getName();
                resolvedProvider = "GCP";
                break;
            } else if (trimmed.equalsIgnoreCase(c.getJiraProjectKeyAzure())) {
                customerId = c.getId();
                customerName = c.getName();
                resolvedProvider = "AZURE";
                break;
            }
        }

        List<Map<String, Object>> issues = jiraClientService.fetchAllIssuesByProject(trimmed);
        jiraBigQueryService.saveDailyJiraIssues(customerId, customerName, resolvedProvider, trimmed, issues, today);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("projectKey", trimmed);
        res.put("providerType", resolvedProvider);
        res.put("customerName", customerName);
        res.put("totalIssuesLoaded", issues.size());
        res.put("date", today.toString());
        return ResponseEntity.ok(res);
    }

    /**
     * 프로젝트 키 기준 이슈 목록 조회 (기본 7일, days=0 또는 미지정 시 전체)
     */
    @GetMapping("/issues")
    public ResponseEntity<Map<String, Object>> getIssues(
            @RequestParam String projectKey,
            @RequestParam(required = false, defaultValue = "7") Integer days) {
        String trimmed = projectKey.trim().toUpperCase();
        List<Map<String, Object>> issues = jiraClientService.fetchIssuesByProjectAndDays(trimmed, (days != null && days > 0) ? days : null);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("projectKey", trimmed);
        res.put("days", days);
        res.put("count", issues.size());
        res.put("issues", issues);
        return ResponseEntity.ok(res);
    }

    /**
     * 고객사 ID 기준 이슈 목록 조회
     */
    @GetMapping("/customer/{customerId}/issues")
    public ResponseEntity<Map<String, Object>> getCustomerIssues(
            @PathVariable String customerId,
            @RequestParam(required = false, defaultValue = "7") Integer days,
            @RequestParam(required = false, defaultValue = "GCP") String providerType) {
        InfraCustomer customer = customerRepository.findById(customerId).orElse(null);
        if (customer == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "Customer not found");
            return ResponseEntity.badRequest().body(err);
        }

        String projectKey = "AZURE".equalsIgnoreCase(providerType) ?
                customer.getJiraProjectKeyAzure() : customer.getJiraProjectKeyGcp();

        if (projectKey == null || projectKey.trim().isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("success", true);
            empty.put("customerName", customer.getName());
            empty.put("providerType", providerType);
            empty.put("projectKey", null);
            empty.put("count", 0);
            empty.put("issues", List.of());
            return ResponseEntity.ok(empty);
        }

        String trimmed = projectKey.trim().toUpperCase();
        List<Map<String, Object>> issues = jiraClientService.fetchIssuesByProjectAndDays(trimmed, (days != null && days > 0) ? days : null);

        Map<String, Object> res = new HashMap<>();
        res.put("success", true);
        res.put("customerName", customer.getName());
        res.put("providerType", providerType);
        res.put("projectKey", trimmed);
        res.put("days", days);
        res.put("count", issues.size());
        res.put("issues", issues);
        return ResponseEntity.ok(res);
    }
}
