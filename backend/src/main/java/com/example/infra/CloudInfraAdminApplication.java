package com.example.infra;

import com.example.infra.config.SchedulingConfig;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class CloudInfraAdminApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(CloudInfraAdminApplication.class, args);
        // 배치 스케줄러 실제 등록 여부 (운영에서 꺼진 채 뜨는 경우를 로그로 식별)
        boolean enabled = !ctx.getBeansOfType(SchedulingConfig.class).isEmpty();
        LoggerFactory.getLogger(CloudInfraAdminApplication.class)
                .info("배치 스케줄러: {} (app.scheduling.enabled={})", enabled ? "활성" : "비활성",
                        ctx.getEnvironment().getProperty("app.scheduling.enabled"));
    }
}
