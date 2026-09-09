package com.example.infra.controller;

import com.example.infra.entity.InfraAuditReport;
import com.example.infra.repository.InfraAuditReportRepository;
import com.example.infra.service.AuditReportService;
import com.example.infra.service.ExcelExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 인프라 점검(Audit) 및 보고서 생성/다운로드 컨트롤러 API
 * 클라우드 점검 요청을 수신하여 비동기로 실행하고, 점검 현황 폴링 및 엑셀/PPTX 파일 다운로드 API를 노출합니다.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AuditController {

    private final AuditReportService auditReportService;
    private final ExcelExportService excelExportService;
    private final InfraAuditReportRepository auditReportRepository;
    private final com.example.infra.repository.InfraEnvironmentRepository environmentRepository;
    private final com.google.cloud.bigquery.BigQuery bigQuery;
    private final com.example.infra.service.BigQueryBatchService bigQueryBatchService;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String targetProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    // BigQuery 스트리밍 인서트 지연으로 인한 다운로드 실패를 피하기 위해 캐시 맵 활용
    private final ConcurrentHashMap<String, InfraAuditReport> reportCache = new ConcurrentHashMap<>();

    // 비동기 실행 상태 트래킹 맵 (reportId -> "IN_PROGRESS" / "COMPLETED" / "FAILED")
    private final ConcurrentHashMap<String, String> auditJobStatus = new ConcurrentHashMap<>();

    /**
     * 비동기 클라우드 인프라 점검(Audit) 실행 API
     * 지정된 환경(environmentId)의 하위 프로젝트들에 대해 GCP API를 호출하여 백그라운드 스레드풀에서 비동기로 점검을 수행합니다.
     * 초기 리포트 마스터 정보를 즉시 생성하여 리턴하며, 완료 여부는 상태 조회 API로 폴링합니다.
     * 
     * @param environmentId 환경 엔티티 ID
     * @param auditorName   점검 실행자 이름 (기본값: System)
     * @return 생성된 점검 보고서 엔티티 (ID 포함)
     */
    @PostMapping("/run/{environmentId}")
    public ResponseEntity<InfraAuditReport> runAudit(@PathVariable String environmentId, @RequestParam(defaultValue = "System") String auditorName) {
        try {
            // 1. 초기 리포트 껍데기 생성 및 DB 저장
            InfraAuditReport report = auditReportService.startAudit(environmentId, auditorName);
            String reportId = report.getId();
            
            // 2. 상태 저장소를 진행 중(IN_PROGRESS) 상태로 설정
            auditJobStatus.put(reportId, "IN_PROGRESS");
            
            // 3. 별도 백그라운드 스레드에서 점검 비동기 수행 시작
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    InfraAuditReport completedReport = auditReportService.executeAuditBackground(report, environmentId);
                    reportCache.put(reportId, completedReport);
                    auditJobStatus.put(reportId, "COMPLETED");
                    
                    // 파일 다운로드 대기 시간(5분) 경과 후 캐시 데이터 메모리 절약을 위해 자동 삭제
                    new Thread(() -> {
                        try { TimeUnit.MINUTES.sleep(5); } catch (Exception e) {}
                        reportCache.remove(reportId);
                        auditJobStatus.remove(reportId);
                    }).start();
                } catch (Exception e) {
                    log.error("Background Audit Failed for reportId: " + reportId, e);
                    auditJobStatus.put(reportId, "FAILED");
                }
            });

            // 4. 리포트 ID를 프론트엔드가 즉시 알 수 있도록 HTTP 200 반환
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.error("Audit Report Generation Initialization Failed for environmentId: " + environmentId, e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 비동기 점검 태스크 처리 상태 조회 API
     * 점검이 아직 실행 중인지(IN_PROGRESS), 정상 완료되었는지(COMPLETED), 실패했는지(FAILED) 파악할 때 사용합니다.
     * 
     * @param reportId 조회하고자 하는 리포트 ID
     * @return 진행 상태 문자열 ("IN_PROGRESS", "COMPLETED", "FAILED" 등)
     */
    @GetMapping("/status/{reportId}")
    public ResponseEntity<String> getAuditStatus(@PathVariable String reportId) {
        String status = auditJobStatus.getOrDefault(reportId, "UNKNOWN");
        return ResponseEntity.ok(status);
    }

    /**
     * 특정 환경에 생성된 과거 전체 점검 리포트 목록 조회 API
     * 
     * @param environmentId 환경 엔티티 ID
     * @return 해당 환경에 귀속된 점검 리포트 리스트
     */
    @GetMapping("/reports/{environmentId}")
    public ResponseEntity<List<InfraAuditReport>> getReports(@PathVariable String environmentId) {
        return ResponseEntity.ok(auditReportRepository.findByEnvironmentIdOrderByAuditDateDesc(environmentId));
    }

    /**
     * 점검 보고서 엑셀 파일 다운로드 API
     * 점검 리포트의 상세 항목을 엑셀 서식 파일로 채운 바이너리를 출력합니다.
     * 단일 프로젝트일 경우 엑셀 파일(.xlsm) 형태로, 다중 프로젝트의 경우 프로젝트별 엑셀 파일을 모아 ZIP 파일로 반환합니다.
     * 
     * @param reportId 점검 리포트 ID
     * @return 엑셀 파일 바이너리 또는 ZIP 파일 바이너리
     */
    @GetMapping("/reports/download/{reportId}")
    public ResponseEntity<byte[]> downloadReport(@PathVariable String reportId) {
        // 캐시 맵에서 먼저 리포트를 찾고 없으면 DB를 조회
        InfraAuditReport cachedReport = reportCache.get(reportId);
        Optional<InfraAuditReport> reportOpt = cachedReport != null ? Optional.of(cachedReport) : auditReportRepository.findById(reportId);

        return reportOpt
                .map(report -> {
                    try {
                        java.util.Map<String, byte[]> excelFiles = excelExportService.exportAuditReportsByProject(report);
                        String customerName = (report.getEnvironment() != null && report.getEnvironment().getCustomer() != null) 
                            ? report.getEnvironment().getCustomer().getName() : "Unknown";
                        String dateStr = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd").format(java.time.LocalDateTime.now());
                        
                        String provider = (report.getEnvironment() != null && report.getEnvironment().getProviderType() != null) 
                            ? report.getEnvironment().getProviderType() : "GCP";
                        String extension = provider.equals("GCP") ? ".xlsm" : ".xlsx";
                        
                        if (excelFiles.size() == 1) {
                            String projectId = excelFiles.keySet().iterator().next();
                            byte[] excelContent = excelFiles.values().iterator().next();
                            
                            String contentType = provider.equals("GCP") 
                                ? "application/vnd.ms-excel.sheet.macroEnabled.12" 
                                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                            
                            String filename = projectId.equals("default") 
                                ? String.format("GCP_점검결과_%s_%s%s", customerName, dateStr, extension)
                                : String.format("GCP_점검결과_%s_%s_%s%s", customerName, projectId, dateStr, extension);
                            
                            return ResponseEntity.ok()
                                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + 
                                            java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20") + "\"")
                                    .contentType(MediaType.parseMediaType(contentType))
                                    .body(excelContent);
                        } else {
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
                                for (java.util.Map.Entry<String, byte[]> entry : excelFiles.entrySet()) {
                                    String pId = entry.getKey();
                                    String fname = String.format("GCP_점검결과_%s_%s_%s%s", customerName, pId, dateStr, extension);
                                    java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(fname);
                                    zos.putNextEntry(ze);
                                    zos.write(entry.getValue());
                                    zos.closeEntry();
                                }
                            }
                            String zipFilename = String.format("GCP_점검결과_%s_%s.zip", customerName, dateStr);
                            return ResponseEntity.ok()
                                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + 
                                            java.net.URLEncoder.encode(zipFilename, "UTF-8").replace("+", "%20") + "\"")
                                    .contentType(MediaType.parseMediaType("application/zip"))
                                    .body(baos.toByteArray());
                        }
                    } catch (Exception e) {
                        log.error("Excel Download Failed for reportId: " + reportId, e);
                        return ResponseEntity.status(500).<byte[]>build();
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 점검 리포트 PPTX(파워포인트) 보고서 다운로드 API
     * 점검된 리포트 데이터를 기반으로 템플릿과 차트를 빌드하여 PPTX 파일로 변환하여 출력합니다.
     * 
     * @param reportId 점검 리포트 ID
     * @return PPTX 파일 바이너리 데이터
     */
    @GetMapping("/reports/download/pptx/{reportId}")
    public ResponseEntity<byte[]> downloadPptxReport(@PathVariable String reportId) {
        InfraAuditReport cachedReport = reportCache.get(reportId);
        Optional<InfraAuditReport> reportOpt = cachedReport != null ? Optional.of(cachedReport) : auditReportRepository.findById(reportId);

        return reportOpt
                .map(report -> {
                    try {
                        String projectId = "";
                        if (report.getDetails() != null && !report.getDetails().isEmpty()) {
                            for (var detail : report.getDetails()) {
                                if (detail.getProjectId() != null && !detail.getProjectId().isEmpty()) {
                                    projectId = detail.getProjectId();
                                    break;
                                }
                            }
                        }

                        byte[] pptxContent = new byte[0];
                        return ResponseEntity.ok()
                                .body(pptxContent);
                    } catch (Exception e) {
                        log.error("PPTX Download Failed for reportId: " + reportId, e);
                        return ResponseEntity.status(500).<byte[]>build();
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 점검 실행 없이 저장된 환경 정보를 기준으로 PPTX 다운로드 API
     * 클라우드 API 실시간 조회를 거치지 않고, 환경 설정에 연결된 프로젝트들을 대상으로 직접 PPTX 양식 파일을 생성합니다.
     * 여러 프로젝트가 귀속되어 있는 경우 ZIP 압축 파일 형태로 각 프로젝트별 PPTX 파일을 묶어 제공합니다.
     * 
     * @param environmentId 환경 엔티티 ID
     * @param targetDate 사용자 선택 기준 날짜 (optional, YYYY-MM-DD 형식)
     * @return 단일 PPTX 또는 ZIP 파일 바이너리
     */
    @GetMapping("/reports/download-pptx-direct/{environmentId}")
    public ResponseEntity<byte[]> downloadPptxReportDirectly(
            @PathVariable String environmentId,
            @RequestParam(required = false) String targetDate) {
        return environmentRepository.findById(environmentId)
                .map(env -> {
                    try {
                        InfraAuditReport dummyReport = new InfraAuditReport();
                        dummyReport.setEnvironment(env);
                        
                        java.time.LocalDateTime actualTargetDateTime = java.time.LocalDateTime.now();
                        java.time.LocalDateTime auditDateTime = java.time.LocalDateTime.now();
                        
                        if (targetDate != null && !targetDate.isEmpty()) {
                            try {
                                java.time.LocalDate selectedDate = java.time.LocalDate.parse(targetDate);
                                actualTargetDateTime = selectedDate.atStartOfDay();
                                // 선택한 달을 포함시키기 위해, 기준일(auditDate)을 다음 달 1일로 설정하여
                                // PptxExportService 내의 이전 달 말일 계산 결과가 선택한 달의 말일이 되게 유도함
                                auditDateTime = selectedDate.withDayOfMonth(1).plusMonths(1).atStartOfDay();
                            } catch (Exception e) {
                                log.error("Failed to parse targetDate: " + targetDate, e);
                            }
                        }
                        
                        dummyReport.setAuditDate(auditDateTime);
                        
                        java.util.List<com.example.infra.entity.CloudProject> projects = env.getProjects();
                        String customerName = (env.getCustomer() != null) ? env.getCustomer().getName() : "Unknown";
                        String provider = (env.getProviderType() != null) ? env.getProviderType() : "GCP";
                        String reportFrequency = (env.getCustomer() != null) ? env.getCustomer().getReportFrequency() : null;
                        
                        return ResponseEntity.ok()
                                .body(new byte[0]);
                    } catch (Exception e) {
                        log.error("Direct PPTX Download Failed for environmentId: " + environmentId, e);
                        return ResponseEntity.status(500).<byte[]>build();
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private String generatePptxFilename(
            String provider, 
            String customerName, 
            String projectId, 
            String reportFrequency, 
            String extension,
            java.time.LocalDateTime targetDateTime) {
            
        java.time.LocalDateTime now = targetDateTime != null ? targetDateTime : java.time.LocalDateTime.now();
        String yearStr = String.format("%04d년", now.getYear());
        String monthStr = String.format("%02d월", now.getMonthValue());
        String dateStr = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        
        String reportType = "MONTHLY".equalsIgnoreCase(reportFrequency) ? "월간보고서" : "분기보고서";
        
        if (projectId != null && !projectId.isEmpty()) {
            return String.format("%s_%s_%s_%s_%s_%s_%s%s", 
                provider, customerName, projectId, yearStr, monthStr, reportType, dateStr, extension);
        } else {
            return String.format("%s_%s_%s_%s_%s_%s%s", 
                provider, customerName, yearStr, monthStr, reportType, dateStr, extension);
        }
    }

    /**
     * BigQuery 연결 및 수집 데이터 적재 테스트용 간이 API
     * BigQuery에 최근 정상 수집 및 입력된 점검 상세 정보 데이터 리스트 중 최근 5건의 이력을 콘솔 및 응답으로 출력합니다.
     * 
     * @return 최근 빅쿼리에 적재 완료된 점검 정보 요약 텍스트
     */
    @GetMapping("/test-bq")
    public ResponseEntity<String> testBq() {
        try {
            com.google.cloud.bigquery.QueryJobConfiguration queryConfig = com.google.cloud.bigquery.QueryJobConfiguration.newBuilder(
                String.format(
                    "SELECT id, report_id, customer_name, audit_date, category, item " +
                    "FROM `%s.%s.infra_audit_detail` " +
                    "ORDER BY audit_date DESC LIMIT 5",
                    targetProjectId, datasetName
                )
            ).build();
            com.google.cloud.bigquery.TableResult results = bigQuery.query(queryConfig);
            StringBuilder sb = new StringBuilder();
            for (com.google.cloud.bigquery.FieldValueList row : results.iterateAll()) {
                sb.append("id: ").append(row.get("id").getStringValue()).append("\n")
                  .append("report_id: ").append(row.get("report_id").isNull() ? "null" : row.get("report_id").getStringValue()).append("\n")
                  .append("customer_name: ").append(row.get("customer_name").isNull() ? "null" : row.get("customer_name").getStringValue()).append("\n")
                  .append("audit_date: ").append(row.get("audit_date").isNull() ? "null" : row.get("audit_date").getStringValue()).append("\n")
                  .append("category: ").append(row.get("category").isNull() ? "null" : row.get("category").getStringValue()).append("\n")
                  .append("item: ").append(row.get("item").isNull() ? "null" : row.get("item").getStringValue()).append("\n")
                  .append("----------------\n");
            }
            return ResponseEntity.ok(sb.toString());
        } catch (Exception e) {
            log.error("Test BQ Failed", e);
            return ResponseEntity.ok("Error: " + e.getMessage());
        }
    }

    /**
     * BigQuery daily snapshot batch manual trigger API
     */
    @PostMapping("/trigger-daily-batch")
    public ResponseEntity<String> triggerDailyBatch() {
        try {
            bigQueryBatchService.runDailySnapshotBatch();
            return ResponseEntity.ok("Daily snapshot batch triggered successfully");
        } catch (Exception e) {
            log.error("Failed to trigger daily snapshot batch manually", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    /**
     * BigQuery past monthly snapshots cleanup manual trigger API
     */
    @PostMapping("/trigger-monthly-cleanup")
    public ResponseEntity<String> triggerMonthlyCleanup(@RequestParam(required = false) String snapshotDate) {
        try {
            String date = (snapshotDate != null && !snapshotDate.isEmpty()) ? snapshotDate : java.time.LocalDate.now().toString();
            bigQueryBatchService.cleanAllPastMonthlySnapshots(date);
            return ResponseEntity.ok("Past monthly snapshot cleanup executed successfully for " + date);
        } catch (Exception e) {
            log.error("Failed to trigger monthly snapshot cleanup manually", e);
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }
}
