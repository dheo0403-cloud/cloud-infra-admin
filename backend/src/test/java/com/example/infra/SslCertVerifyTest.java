package com.example.infra;

import com.example.infra.entity.InfraEnvironment;
import com.example.infra.entity.CloudProject;
import com.example.infra.repository.InfraEnvironmentRepository;
import com.example.infra.service.EncryptionService;
import com.example.infra.service.GcpResourceFetcher;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.compute.v1.SslCertificate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@SpringBootTest
public class SslCertVerifyTest {

    @Autowired
    private InfraEnvironmentRepository environmentRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private GcpResourceFetcher gcpResourceFetcher;

    @Test
    public void verifySslCertificates() throws Exception {
        System.out.println("====== START SSL VERIFICATION TEST ======");
        
        List<InfraEnvironment> envs = environmentRepository.findAll();
        for (InfraEnvironment env : envs) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) {
                continue;
            }
            
            System.out.println("Environment: " + env.getEnvironmentName() + " (ID: " + env.getId() + ")");
            String encSecret = env.getEncryptedSecret();
            if (encSecret == null) {
                System.out.println("  No credentials found");
                continue;
            }
            
            String decSecret = encryptionService.decrypt(encSecret);
            GoogleCredentials credentials = GoogleCredentials.fromStream(
                new ByteArrayInputStream(decSecret.getBytes(StandardCharsets.UTF_8))
            ).createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
            
            List<CloudProject> projects = env.getProjects();
            if (projects == null || projects.isEmpty()) {
                System.out.println("  No projects registered");
                continue;
            }
            
            for (CloudProject proj : projects) {
                String projectId = proj.getProjectId();
                System.out.println("  Project: " + projectId);
                try {
                    List<SslCertificate> certs = gcpResourceFetcher.getSslCertificates(credentials, projectId);
                    for (SslCertificate cert : certs) {
                        System.out.println("    Name: " + cert.getName());
                        System.out.println("      Type: " + cert.getType());
                        System.out.println("      ExpireTime: " + cert.getExpireTime());
                        System.out.println("      CreationTimestamp: " + (cert.hasCreationTimestamp() ? cert.getCreationTimestamp() : "N/A"));
                    }
                } catch (Exception e) {
                    System.out.println("      Error fetching certs: " + e.getMessage());
                }
            }
        }
        System.out.println("====== END SSL VERIFICATION TEST ======");
    }
}
