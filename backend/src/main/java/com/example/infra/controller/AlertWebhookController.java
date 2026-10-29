package com.example.infra.controller;

import com.example.infra.service.AlertRelayService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 클라우드 알람 수신기 (Azure 액션 그룹 웹훅 → MARK 전달).
 * 앱에 별도 인증이 없어 주소의 token으로 확인한다. 토큰 설정이 비어 있으면 모든 요청을 거부한다.
 * app.alert.receiver.enabled=true 일 때만 등록 (운영 Dockerfile에서만 켬)
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.alert.receiver.enabled", havingValue = "true")
public class AlertWebhookController {

    private final AlertRelayService relayService;

    @Value("${app.alert.receiver.token:}")
    private String receiverToken;

    @PostMapping("/azure")
    public ResponseEntity<Map<String, Object>> azure(@RequestParam(defaultValue = "") String token,
                                                     @RequestBody JsonNode payload) {
        if (!validToken(token)) return ResponseEntity.status(401).build();
        // MARK 전송 실패도 200으로 응답 (Azure 재전송으로 인한 중복 방지). 실패는 alert_event 행으로 추적
        return ResponseEntity.ok(Map.of("result", relayService.relayAzure(payload)));
    }

    private boolean validToken(String token) {
        return !receiverToken.isEmpty() && MessageDigest.isEqual(
            receiverToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
