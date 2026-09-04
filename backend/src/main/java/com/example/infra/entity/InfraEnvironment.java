package com.example.infra.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
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
public class InfraEnvironment {

    private String id;

    @JsonBackReference
    private InfraCustomer customer;

    private String providerType; // GCP, AZURE

    private String environmentName; // e.g., Production, Staging

    @Builder.Default
    @JsonManagedReference
    private List<CloudProject> projects = new ArrayList<>();

    private String encryptedSecret; // GCP Service Account JSON or Azure Client Secret

    // Azure Specific
    private String azureTenantId;
    private String azureClientId;

    private LocalDateTime createdAt;

    @Builder.Default
    private Boolean isDeleted = false;
}
