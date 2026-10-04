package com.example.infra.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 배치는 app.scheduling.enabled=true일 때만 실행한다.
 * 기본값은 꺼짐(로컬 JAR, 테스트). 운영 Docker 이미지는 Dockerfile의 APP_SCHEDULING_ENABLED=true로 켠다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true")
public class SchedulingConfig {
}
