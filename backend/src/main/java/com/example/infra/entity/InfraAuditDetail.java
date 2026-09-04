package com.example.infra.entity;


import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfraAuditDetail {

    private String id;

    @com.fasterxml.jackson.annotation.JsonBackReference
    private InfraAuditReport report;

    private String category; // e.g., "GCP 인프라 운영 정보", "보안 감사"

    private String item; // e.g., "Region", "Uptime Check"

    private String status; // PASS, FAIL, WARNING, INFO

    private String resultText; // The actual value or finding

    private String remediation; // Column H (Action Guidance)

    private String checkResult; // For Excel Column F (Check Result)

    private String projectId; // 프로젝트별 구분을 위한 ID
}
