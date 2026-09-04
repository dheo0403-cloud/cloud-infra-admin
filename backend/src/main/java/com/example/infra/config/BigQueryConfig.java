package com.example.infra.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;

@Configuration
public class BigQueryConfig {

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String projectId;

    @Value("${spring.cloud.gcp.credentials.location:classpath:gcp-credentials.json}")
    private Resource credentialsResource;

    @Bean
    public BigQuery bigQuery() throws IOException {
        GoogleCredentials credentials;
        if (credentialsResource != null && credentialsResource.exists()) {
            credentials = GoogleCredentials.fromStream(credentialsResource.getInputStream());
        } else {
            try {
                credentials = GoogleCredentials.getApplicationDefault();
            } catch (Exception e) {
                credentials = null;
            }
        }
        
        BigQueryOptions.Builder builder = BigQueryOptions.newBuilder().setProjectId(projectId);
        if (credentials != null) {
            builder.setCredentials(credentials);
        }
        return builder.build().getService();
    }
}
