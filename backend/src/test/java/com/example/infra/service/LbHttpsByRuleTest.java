package com.example.infra.service;

import com.google.cloud.compute.v1.ForwardingRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// LB 5XX HTTP/HTTPS 구분: 포워딩 규칙이 연결된 프록시 종류 기준
class LbHttpsByRuleTest {

    private ForwardingRule rule(String name, String target) {
        ForwardingRule.Builder b = ForwardingRule.newBuilder().setName(name);
        if (target != null) b.setTarget(target);
        return b.build();
    }

    @Test
    void 프록시_종류로_HTTP_HTTPS를_나누고_그외는_제외한다() {
        Map<String, Boolean> map = BigQueryBatchService.lbHttpsByRule(List.of(
                // 이름에 https가 있어도 HTTP 프록시면 HTTP
                rule("gcp-iis-http-https-lb-front-80", "https://www.googleapis.com/compute/v1/projects/p/global/targetHttpProxies/gcp-iis-target-proxy"),
                rule("gcp-iis-http-https-lb-front-443", "https://www.googleapis.com/compute/v1/projects/p/global/targetHttpsProxies/gcp-iis-target-proxy-2"),
                rule("tcp-lb", "https://www.googleapis.com/compute/v1/projects/p/global/targetTcpProxies/tcp"),
                rule("no-target", null)));

        assertEquals(Map.of("gcp-iis-http-https-lb-front-80", false, "gcp-iis-http-https-lb-front-443", true), map);
    }
}
