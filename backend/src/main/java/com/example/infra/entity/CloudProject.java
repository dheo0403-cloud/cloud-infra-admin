package com.example.infra.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloudProject {

    private String id;

    @JsonBackReference
    private InfraEnvironment environment;

    private String projectId; // Project ID for GCP or Subscription ID for Azure
}
