package com.example.infra;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CloudInfraAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudInfraAdminApplication.class, args);
    }
}
