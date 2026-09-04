package com.example.infra.entity;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import lombok.*;

import java.time.LocalDateTime;

import java.util.ArrayList;
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

    @Builder.Default
    @JsonManagedReference
    private List<InfraEnvironment> environments = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Builder.Default
    private Boolean isDeleted = false;
}
