package com.example.infra.controller;

import com.example.infra.dto.MonthlyReportDto;
import com.example.infra.service.MonthlyReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;


@Slf4j
@RestController
@RequestMapping("/api/reports/gcp")
@RequiredArgsConstructor
public class MonthlyReportController {

    private final MonthlyReportService monthlyReportService;
    private final com.example.infra.service.BigQueryBatchService bigQueryBatchService;
    private final com.example.infra.service.VertexAiGeminiService vertexAiGeminiService;

    @RequestMapping(value = "/run-batch", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Map<String, String>> triggerBatchManually() {
        log.info("Manually triggering Daily Snapshot Batch via API...");
        try {
            bigQueryBatchService.runDailySnapshotBatch();
            return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Daily Snapshot Batch triggered successfully"));
        } catch (Exception e) {
            log.error("Manual batch trigger failed", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }

    @PostMapping("/generate-ai-summary")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, String>> generateAiSummary(@RequestBody Map<String, Object> request) {
        log.info("Request On-Demand AI Executive Summary Generation");
        List<String> recSecurity = (List<String>) request.getOrDefault("recSecurity", java.util.Collections.emptyList());
        List<String> recCost = (List<String>) request.getOrDefault("recCost", java.util.Collections.emptyList());
        List<String> recPerformance = (List<String>) request.getOrDefault("recPerformance", java.util.Collections.emptyList());

        String execSecurity = vertexAiGeminiService.summarizeCategory("보안 및 컴플라이언스", recSecurity, null);
        String execCost = vertexAiGeminiService.summarizeCategory("비용 최적화", recCost, null);
        String execPerformance = vertexAiGeminiService.summarizeCategory("성능 및 안정성", recPerformance, null);

        Map<String, String> result = new HashMap<>();
        result.put("execSecurity", execSecurity);
        result.put("execCost", execCost);
        result.put("execPerformance", execPerformance);
        return ResponseEntity.ok(result);
    }


    @RequestMapping(value = "/monthly", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<MonthlyReportDto> getMonthlyReport(
            @RequestParam(required = false, defaultValue = "MegazoneCloud Customer") String customerName,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String targetYearMonth,
            @RequestBody(required = false) Map<String, String> body) {
        
        if (body != null && !body.isEmpty()) {
            if (body.containsKey("customerName") && body.get("customerName") != null) {
                customerName = body.get("customerName");
            }
            if (body.containsKey("projectId") && body.get("projectId") != null) {
                projectId = body.get("projectId");
            }
            if (body.containsKey("targetYearMonth") && body.get("targetYearMonth") != null) {
                targetYearMonth = body.get("targetYearMonth");
            }
        }

        long reqStart = System.currentTimeMillis();
        log.info("[REPORT-API] 📥 보고서 생성 요청 수신 (GET/POST) - customer: {}, project: {}, yearMonth: {}", customerName, projectId, targetYearMonth);
        MonthlyReportDto report = monthlyReportService.generateMonthlyReport(customerName, projectId, targetYearMonth);
        long reqElapsed = System.currentTimeMillis() - reqStart;
        log.info("[REPORT-API] 📤 보고서 응답 완료 - project: {}, 총 소요시간: {}초 ({}ms)", 
                projectId, String.format("%.1f", reqElapsed / 1000.0), reqElapsed);
        return ResponseEntity.ok(report);
    }

    @PostMapping("/monthly/save")
    public ResponseEntity<Map<String, String>> saveCustomReportText(@RequestBody Map<String, String> request) {
        String projectId = request.get("projectId");
        String targetYearMonth = request.get("targetYearMonth");
        String customComments = request.get("customComments");
        String customRecommendations = request.get("customRecommendations");
        String customSupportLogs = request.get("customSupportLogs");
        String mspSalesContact = request.get("mspSalesContact");
        String mspTechContact = request.get("mspTechContact");
        String mspGrade = request.get("mspGrade");
        String customRecSecurity = request.get("customRecSecurity");
        String customRecCost = request.get("customRecCost");
        String customRecPerformance = request.get("customRecPerformance");

        String customExecSecurity = request.get("customExecSecurity");
        String customExecCost = request.get("customExecCost");
        String customExecPerformance = request.get("customExecPerformance");
        String customWorkLogs = request.get("customWorkLogs");

        monthlyReportService.saveCustomText(projectId, targetYearMonth, customComments, customRecommendations, customSupportLogs, mspSalesContact, mspTechContact, mspGrade, customRecSecurity, customRecCost, customRecPerformance, customExecSecurity, customExecCost, customExecPerformance, customWorkLogs);
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Custom report text saved successfully"));
    }
}
