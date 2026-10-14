package com.example.infra.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import lombok.*;

import java.time.LocalDateTime;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InfraCustomer {

    private String id;

    private String name;

    private String contactPerson;

    private String contactEmail;

    private String reportFrequency;

    private String mspGrade;

    private String mspSalesRep;

    private String mspRep;

    private String jiraProjectKeyGcp;

    private String jiraProjectKeyAzure;

    // 기본값을 두지 않는다: 요청 JSON에 environments 키가 없으면 null로 남아야 저장 시 기존 환경을 지우지 않음
    @JsonManagedReference
    private List<InfraEnvironment> environments;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Builder.Default
    private Boolean isDeleted = false;
}
