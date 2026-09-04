package com.example.infra.service;

import com.example.infra.repository.InfraEnvironmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledReportService {

    private final InfraEnvironmentRepository environmentRepository;

    // 매월 1일 자정 (0 0 0 1 * ?)
    @Scheduled(cron = "0 0 0 1 * ?")
    public void generateMonthlyReports() {
        log.info("Starting scheduled report generation check for active environments...");
    }
}
