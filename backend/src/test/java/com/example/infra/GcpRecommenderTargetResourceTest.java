package com.example.infra;

import com.example.infra.service.GcpRecommenderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GcpRecommenderTargetResourceTest {

    @Test
    @DisplayName("URI 기반 대상 리소스명(VM, SA, Disk, SQL, IP) 추출 검증")
    public void testExtractResourceNameFromUri() {
        // 1. VM Instance URI
        String vmUri = "//compute.googleapis.com/projects/valofe-prod/zones/asia-northeast3-a/instances/prod-web-server-01";
        assertEquals("prod-web-server-01", GcpRecommenderService.extractResourceName(vmUri));

        // 2. IAM Service Account URI
        String saUri = "//iam.googleapis.com/projects/valofe-prod/serviceAccounts/deployer-sa@valofe-prod.iam.gserviceaccount.com";
        assertEquals("deployer-sa@valofe-prod.iam.gserviceaccount.com", GcpRecommenderService.extractResourceName(saUri));

        // 3. Persistent Disk URI
        String diskUri = "//compute.googleapis.com/projects/valofe-prod/zones/asia-northeast3-b/disks/unused-backup-disk";
        assertEquals("unused-backup-disk", GcpRecommenderService.extractResourceName(diskUri));

        // 4. Static IP URI
        String ipUri = "//compute.googleapis.com/projects/valofe-prod/regions/asia-northeast3/addresses/legacy-api-ip";
        assertEquals("legacy-api-ip", GcpRecommenderService.extractResourceName(ipUri));

        // 5. Cloud Storage Bucket URI
        String bucketUri = "//storage.googleapis.com/projects/_/buckets/valofe-public-asset-bucket";
        assertEquals("valofe-public-asset-bucket", GcpRecommenderService.extractResourceName(bucketUri));
    }

    @Test
    @DisplayName("설명(Description) 텍스트로부터 대상 리소스명 정규식 Fallback 추출 검증")
    public void testExtractTargetFromDescription() {
        // 1. 고정 IP 따옴표 패턴
        String ipDesc = "Save cost by deleting idle IP address '34.64.120.55'";
        assertEquals("34.64.120.55", GcpRecommenderService.extractTargetFromDescription(ipDesc));

        // 2. Cloud SQL 인스턴스 패턴
        String sqlDesc = "For your Cloud SQL instance: valofe-mysql-master, table_open_cache is underutilized";
        assertEquals("valofe-mysql-master", GcpRecommenderService.extractTargetFromDescription(sqlDesc));

        // 3. 이미 [대상: xxx] 태그가 포함된 경우
        String taggedDesc = "[HIGH] [대상: prod-api-01] 머신 유형을 e2-standard-2로 축소하십시오.";
        assertEquals("prod-api-01", GcpRecommenderService.extractTargetFromDescription(taggedDesc));
    }

    @Test
    @DisplayName("권고사항 표준 텍스트 포맷 규격화 ([우선순위] [대상: 리소스명] 메시지) 검증")
    public void testFormatRecommendationText() {
        // 1. 우선순위와 대상 리소스가 모두 있는 경우
        String formatted = GcpRecommenderService.formatRecommendationText("HIGH", "prod-web-01", "머신 유형을 n1-standard-4에서 e2-standard-2(으)로 축소하여 비용을 절감하십시오.");
        assertEquals("[HIGH] [대상: prod-web-01] 머신 유형을 n1-standard-4에서 e2-standard-2(으)로 축소하여 비용을 절감하십시오.", formatted);

        // 2. 대상 리소스가 없는 전역 권고의 경우
        String globalFormatted = GcpRecommenderService.formatRecommendationText("MEDIUM", "", "GCS 버킷의 Public Access Prevention 설정을 활성화하십시오.");
        assertEquals("[MEDIUM] GCS 버킷의 Public Access Prevention 설정을 활성화하십시오.", globalFormatted);

        // 3. 이미 우선순위 태그가 중복 붙은 경우의 정규화
        String dedupeFormatted = GcpRecommenderService.formatRecommendationText("CRITICAL", "sa-batch@valofe.com", "[CRITICAL] 관찰 기간 동안 사용되지 않은 IAM 역할입니다.");
        assertEquals("[CRITICAL] [대상: sa-batch@valofe.com] 관찰 기간 동안 사용되지 않은 IAM 역할입니다.", dedupeFormatted);
    }

    @Test
    @DisplayName("GCP IAM Recommender JSON 응답(content.overview.member 등)에서 대상 계정 추출 검증")
    public void testExtractIamAccountFromOverviewAndOperations() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        // 1. content.overview.member 가 있는 경우
        String json1 = "{" +
                "  \"name\": \"projects/659624710484/locations/global/recommenders/google.iam.policy.Recommender/recommendations/rec-1\"," +
                "  \"description\": \"Remove unused role from user\"," +
                "  \"content\": {" +
                "    \"overview\": {" +
                "      \"member\": \"user:hong@megazone.com\"," +
                "      \"role\": \"roles/editor\"" +
                "    }" +
                "  }," +
                "  \"targetResources\": [\"//cloudresourcemanager.googleapis.com/projects/659624710484\"]" +
                "}";
        com.fasterxml.jackson.databind.JsonNode rec1 = mapper.readTree(json1);
        String target1 = GcpRecommenderService.extractTargetAccountOrResource(rec1, "google.iam.policy.Recommender", "Remove unused role");
        assertEquals("user:hong@megazone.com", target1);

        // 2. operations pathFilters 에 계정이 있는 경우
        String json2 = "{" +
                "  \"name\": \"projects/659624710484/locations/global/recommenders/google.iam.policy.Recommender/recommendations/rec-2\"," +
                "  \"content\": {" +
                "    \"operationGroups\": [{" +
                "      \"operations\": [{" +
                "        \"resource\": \"//cloudresourcemanager.googleapis.com/projects/659624710484\"," +
                "        \"pathFilters\": {\"/iamPolicy/bindings/*/members/*\": \"serviceAccount:deployer@my-proj.iam.gserviceaccount.com\"}" +
                "      }]" +
                "    }]" +
                "  }," +
                "  \"targetResources\": [\"//cloudresourcemanager.googleapis.com/projects/659624710484\"]" +
                "}";
        com.fasterxml.jackson.databind.JsonNode rec2 = mapper.readTree(json2);
        String target2 = GcpRecommenderService.extractTargetAccountOrResource(rec2, "google.iam.policy.Recommender", "");
        assertEquals("serviceAccount:deployer@my-proj.iam.gserviceaccount.com", target2);
    }
}
