package com.example.infra;

import com.example.infra.entity.InfraEnvironment;
import com.example.infra.repository.InfraEnvironmentRepository;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.compute.v1.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Disabled("실서버 GCP BigQuery 연동 테스트 (로컬 빌드 시 제외)")
public class LBAuditTest {

    @Test
    public void testAuditLBs() throws Exception {
        System.out.println("=== STARTING ALL LB AUDIT TEST ===");
        
        InputStream credStream = new ClassPathResource("gcp-credentials.json").getInputStream();
        GoogleCredentials credentials = GoogleCredentials.fromStream(credStream);
        BigQuery bigQuery = BigQueryOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId("mzc-gcp-managed")
                .build()
                .getService();

        InfraEnvironmentRepository repo = new InfraEnvironmentRepository(bigQuery);
        org.springframework.test.util.ReflectionTestUtils.setField(repo, "targetProjectId", "mzc-gcp-managed");
        org.springframework.test.util.ReflectionTestUtils.setField(repo, "datasetName", "infra_admin_dataset");
        
        List<InfraEnvironment> envs = repo.findAll();
        System.out.println("Found " + envs.size() + " environments.");
        
        String masterKey = "MZC-INFRA-SECRET-KEY";
        String salt = "6d7a633132333435";
        TextEncryptor encryptor = Encryptors.text(masterKey, salt);
        
        for (InfraEnvironment env : envs) {
            if (!"GCP".equalsIgnoreCase(env.getProviderType())) continue;
            
            System.out.println("======================================================================");
            System.out.println("Env Name: " + env.getEnvironmentName());
            
            String decryptedSecret = null;
            try {
                decryptedSecret = encryptor.decrypt(env.getEncryptedSecret());
            } catch (Exception e) {
                System.out.println("Failed to decrypt secret: " + e.getMessage());
                continue;
            }
            
            GoogleCredentials gcpCreds = GoogleCredentials.fromStream(new ByteArrayInputStream(decryptedSecret.getBytes()))
                    .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
            
            if (env.getProjects() == null || env.getProjects().isEmpty()) {
                System.out.println("No projects configured");
                continue;
            }
            
            String projectId = env.getProjects().get(0).getProjectId();
            System.out.println("Project ID: " + projectId);
            
            List<ForwardingRule> rules = new ArrayList<>();
            try (ForwardingRulesClient client = ForwardingRulesClient.create(
                    ForwardingRulesSettings.newBuilder().setCredentialsProvider(() -> gcpCreds).build())) {
                for (Map.Entry<String, ForwardingRulesScopedList> entry : client.aggregatedList(projectId).iterateAll()) {
                    if (entry.getValue().getForwardingRulesList() == null) continue;
                    rules.addAll(entry.getValue().getForwardingRulesList());
                }
            } catch (Exception e) {
                System.out.println("Failed to fetch forwarding rules: " + e.getMessage());
                continue;
            }
            
            System.out.println("Found " + rules.size() + " forwarding rules:");
            for (ForwardingRule rule : rules) {
                String target = rule.getTarget();
                String scheme = rule.getLoadBalancingScheme();
                System.out.println(String.format("  - Name: %s | Scheme: %s | Target: %s | IP: %s", 
                    rule.getName(), scheme, target, rule.getIPAddress()));
            }
        }
        System.out.println("=== END OF ALL LB AUDIT TEST ===");
    }
}
