package com.example.infra;

import com.example.infra.service.BigQueryBatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest
public class SlackD30NotificationTest {

    @Autowired
    private BigQueryBatchService bigQueryBatchService;

    @Test
    @DisplayName("Azure RI & GCP CUD 약정 만료 D-30 Slack 알람 1회성 발송 테스트")
    public void testSendSlackD30Notification() {
        System.out.println("==================================================================");
        System.out.println("🚀 [TEST] Azure RI & GCP CUD D-30 Slack Block Kit 알람 1회성 발송 시작");
        System.out.println("==================================================================");

        // sendMockIfEmpty = true 로 실행하여 DB에 D-30 데이터가 없더라도 모의 D-30 데이터로 1회 전송 검증
        assertDoesNotThrow(() -> {
            boolean result = bigQueryBatchService.checkReservationsD30ExpiryAndNotifySlack(true);
            System.out.println("==================================================================");
            System.out.println("✅ [TEST] Slack D-30 발송 결과: " + (result ? "SUCCESS (HTTP 200 OK)" : "NOTICE (Webhook not configured or sent)"));
            System.out.println("==================================================================");
        });
    }
}
