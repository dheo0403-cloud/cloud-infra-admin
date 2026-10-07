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

    private ForwardingRule rule(String name, String scheme, String target, String backendService) {
        ForwardingRule.Builder b = ForwardingRule.newBuilder().setName(name).setLoadBalancingScheme(scheme);
        if (target != null) b.setTarget(target);
        if (backendService != null) b.setBackendService(backendService);
        return b.build();
    }

    @Test
    void 외부_내부_Application_LB를_따로_센다() {
        String g = "https://www.googleapis.com/compute/v1/projects/p/global/";
        Map<String, Integer> c = BigQueryBatchService.summarizeLoadBalancers(List.of(
                // 외부 ALB: 같은 URL Map의 HTTP·HTTPS 규칙 → 1개
                rule("ext-80", "EXTERNAL", g + "targetHttpProxies/hp", null),
                rule("ext-443", "EXTERNAL", g + "targetHttpsProxies/sp", null),
                // 내부 ALB
                rule("int-alb", "INTERNAL_MANAGED", "https://www.googleapis.com/compute/v1/projects/p/regions/r/targetHttpsProxies/ip", null),
                // 외부 네트워크 LB (Application 아님)
                rule("ext-nlb", "EXTERNAL", null, "https://www.googleapis.com/compute/v1/projects/p/regions/r/backendServices/bs")),
                Map.of("hp", "um"), Map.of("sp", "um", "ip", "ium"));

        assertEquals(3, c.get("LoadBalancer"));
        assertEquals(2, c.get("LB_Access_External"));
        assertEquals(1, c.get("LB_App_External"));
        assertEquals(1, c.get("LB_App_Internal"));
        assertEquals(1, c.get("LB_Type_Network"));
    }
}
