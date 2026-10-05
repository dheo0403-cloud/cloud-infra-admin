package com.example.infra.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloudProject {

    // 프로젝트는 infra_environment.project_ids(문자열 배열)로만 저장되므로 projectId가 유일한 식별자
    @JsonBackReference
    private InfraEnvironment environment;

    private String projectId; // Project ID for GCP or Subscription ID for Azure
}
