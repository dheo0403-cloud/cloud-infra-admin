package com.example.infra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Azure 예약(RI) 응답 properties 파싱 검증 (응답 형식은 Microsoft.Capacity/reservations 2022-11-01 기준)
class AzureRiParsingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void 단일_구독_범위는_적용_구독을_쓴다() throws Exception {
        JsonNode p = props("{\"appliedScopeType\":\"Single\",\"appliedScopeProperties\":{\"subscriptionId\":\"/subscriptions/sub-prod\",\"displayName\":\"Prod\"},"
                + "\"billingScopeId\":\"/subscriptions/sub-billing\",\"benefitStartTime\":\"2025-11-05T05:14:57Z\",\"purchaseDate\":\"2025-11-04\","
                + "\"effectiveDateTime\":\"2026-08-02T07:38:54Z\",\"term\":\"P1Y\",\"renew\":true}");
        assertEquals("sub-prod", ReservationService.azureRiSubscription(p));
        assertEquals("2025-11-05", ReservationService.azureRiStartDate(p));
        assertEquals("P1Y (자동 갱신)", ReservationService.azureRiPlan(p));
    }

    @Test
    void 리소스그룹_범위는_구독을_추출한다() throws Exception {
        JsonNode p = props("{\"appliedScopeProperties\":{\"resourceGroupId\":\"/subscriptions/sub-rg/resourceGroups/rg1\"},\"term\":\"P3Y\",\"renew\":false}");
        assertEquals("sub-rg", ReservationService.azureRiSubscription(p));
        assertEquals("P3Y", ReservationService.azureRiPlan(p));
    }

    @Test
    void 공유_범위는_결제_구독을_쓰고_없으면_빈값() throws Exception {
        assertEquals("sub-billing", ReservationService.azureRiSubscription(props("{\"appliedScopeType\":\"Shared\",\"billingScopeId\":\"/subscriptions/sub-billing\"}")));
        assertEquals("", ReservationService.azureRiSubscription(props("{\"appliedScopeType\":\"Shared\"}")));
    }

    @Test
    void 시작일은_혜택시작_없으면_구매일() throws Exception {
        assertEquals("2025-11-04", ReservationService.azureRiStartDate(props("{\"purchaseDate\":\"2025-11-04\"}")));
        assertEquals("", ReservationService.azureRiStartDate(props("{}")));
    }

    @Test
    void 중복키는_기존처럼_effectiveDateTime_날짜를_쓴다() throws Exception {
        JsonNode ri = props("{\"name\":\"id-1\"}");
        JsonNode p = props("{\"displayName\":\"VM_RI\",\"effectiveDateTime\":\"2025-11-26T01:02:03Z\"}");
        assertEquals("VM_RI_2025-11-26_Standard_E8bds_v5_", ReservationService.azureRiDedupKey(ri, p, "Standard_E8bds_v5", ""));
        assertEquals("id-1__sku_loc", ReservationService.azureRiDedupKey(ri, props("{}"), "sku", "loc"));
    }
}
