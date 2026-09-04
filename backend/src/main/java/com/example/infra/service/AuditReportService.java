package com.example.infra.service;

import com.example.infra.entity.InfraAuditDetail;
import com.example.infra.entity.InfraAuditReport;
import com.example.infra.entity.InfraEnvironment;
import com.example.infra.repository.InfraAuditReportRepository;
import com.example.infra.repository.InfraEnvironmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditReportService {

    private final GcpAuditService gcpAuditService;
    private final InfraAuditReportRepository auditReportRepository;
    private final InfraEnvironmentRepository environmentRepository;
    private final com.example.infra.repository.InfraAuditDetailRepository auditDetailRepository;

    public InfraAuditReport startAudit(String environmentId, String auditorName) {
        InfraEnvironment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + environmentId));

        InfraAuditReport report = InfraAuditReport.builder()
                .environment(environment)
                .auditDate(java.time.LocalDateTime.now())
                .auditorName(auditorName)
                .summary("Infrastructure audit for " + environment.getEnvironmentName() + " (" + environment.getProviderType() + ")")
                .build();

        return auditReportRepository.save(report);
    }

    public InfraAuditReport executeAuditBackground(InfraAuditReport report, String environmentId) {
        List<InfraAuditDetail> details = gcpAuditService.performGcpAudit(environmentId, report);
        
        for (InfraAuditDetail detail : details) {
            detail.setReport(report);
        }
        auditDetailRepository.saveAll(details);
        
        report.setDetails(details);
        return report;
    }
}
