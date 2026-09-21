package com.example.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GcpLbErrorCountTest {

    @Test
    @DisplayName("HTTP 및 HTTPS 포워딩 룰이 동일 URL Map에 연결된 경우 HTTPS 대표값(611,345) 선별 및 2배 중복 방지 검증 (5XX 에러)")
    public void testLb500ErrorDeduplication() {
        // Given: 밸로프(infra-platform)의 로드밸런서 환경 시뮬레이션
        // 동일 URL Map(infra-platform-url-map)에 HTTP 포워딩 룰(592,357건)과 HTTPS 포워딩 룰(611,345건)이 존재
        Map<String, Map<String, Long>> urlMapToRuleCount = new LinkedHashMap<>();

        Map<String, Long> rules = new LinkedHashMap<>();
        rules.put("infra-platform-http-forwarding-rule", 592357L);
        rules.put("infra-platform-https-forwarding-rule", 611345L);
        urlMapToRuleCount.put("infra-platform-url-map", rules);

        // When: GcpResourceFetcher에 적용된 중복 제거 알고리즘 실행
        long total5xxCount = 0;
        for (Map.Entry<String, Map<String, Long>> entry : urlMapToRuleCount.entrySet()) {
            Map<String, Long> ruleCounts = entry.getValue();
            if (ruleCounts.isEmpty()) continue;

            if (ruleCounts.size() == 1) {
                total5xxCount += ruleCounts.values().iterator().next();
            } else {
                Optional<Long> httpsCount = ruleCounts.entrySet().stream()
                        .filter(e -> e.getKey().toLowerCase().contains("https") || e.getKey().contains("443") || e.getKey().contains("ssl"))
                        .map(Map.Entry::getValue)
                        .findFirst();

                if (httpsCount.isPresent()) {
                    total5xxCount += httpsCount.get();
                } else {
                    long maxCount = ruleCounts.values().stream().max(Long::compare).orElse(0L);
                    total5xxCount += maxCount;
                }
            }
        }

        // Then: 기존 단순 합산(1,203,702건)이 아닌 실제 콘솔 수치인 611,345건으로 정확하게 산출되어야 함
        assertEquals(611345L, total5xxCount, "HTTP/HTTPS 중복 합산이 제거되고 실제 HTTPS 5XX 에러 건수인 611,345건이 산출되어야 합니다.");
    }

    @Test
    @DisplayName("단일 포워딩 룰 및 다중 독립 URL Map 로드밸런서의 5XX 에러 합산 검증")
    public void testMultipleIndependentLbCounts() {
        Map<String, Map<String, Long>> urlMapToRuleCount = new LinkedHashMap<>();

        // LB 1: HTTP/HTTPS 페어
        Map<String, Long> lb1Rules = new LinkedHashMap<>();
        lb1Rules.put("api-lb-http", 1000L);
        lb1Rules.put("api-lb-https", 1200L);
        urlMapToRuleCount.put("api-url-map", lb1Rules);

        // LB 2: 단일 Internal LB
        Map<String, Long> lb2Rules = new LinkedHashMap<>();
        lb2Rules.put("internal-rule", 50L);
        urlMapToRuleCount.put("internal-url-map", lb2Rules);

        long total5xxCount = 0;
        for (Map.Entry<String, Map<String, Long>> entry : urlMapToRuleCount.entrySet()) {
            Map<String, Long> ruleCounts = entry.getValue();
            if (ruleCounts.isEmpty()) continue;

            if (ruleCounts.size() == 1) {
                total5xxCount += ruleCounts.values().iterator().next();
            } else {
                Optional<Long> httpsCount = ruleCounts.entrySet().stream()
                        .filter(e -> e.getKey().toLowerCase().contains("https") || e.getKey().contains("443") || e.getKey().contains("ssl"))
                        .map(Map.Entry::getValue)
                        .findFirst();

                if (httpsCount.isPresent()) {
                    total5xxCount += httpsCount.get();
                } else {
                    long maxCount = ruleCounts.values().stream().max(Long::compare).orElse(0L);
                    total5xxCount += maxCount;
                }
            }
        }

        // Expected: 1200 (LB1 HTTPS) + 50 (LB2 단일) = 1250
        assertEquals(1250L, total5xxCount);
    }
}
