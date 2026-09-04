package com.example.infra.entity;

import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfraAuditReport {

    private String id;

    private InfraEnvironment environment;

    private java.time.LocalDateTime auditDate;

    private String auditorName;

    private String summary;

    @Builder.Default
    private List<InfraAuditDetail> details = new ArrayList<>();
}
