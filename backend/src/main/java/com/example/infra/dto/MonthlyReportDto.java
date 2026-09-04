package com.example.infra.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonthlyReportDto {

    private String customerName;
    private String projectId;
    private String reportMonth;
    private List<String> months;

    private String mspSalesContact;
    private String mspTechContact;
    private String mspGrade;
    private String reportPeriod;
    private String snapshotTime;
    private String regionLocation;

    // Metric Total & Delta for Trend Badges
    private int vmTotal;
    private int vmTotalDelta;
    private int sqlTotal;
    private int sqlTotalDelta;
    private int diskTotal;
    private int diskTotalDelta;
    private int bucketTotal;
    private int bucketTotalDelta;
    private int gkeTotal;
    private int gkeTotalDelta;
    private int lbTotal;
    private int lbTotalDelta;

    // IAM & Security Metrics
    private Map<String, List<Integer>> iamSummary;
    private Map<String, Integer> saSecurity;
    private int userPasswordOver90;
    private int mfaDisabled;
    private int ownerSaCount;
    private int ownerUserCount;

    // Detail Monitoring Summaries
    private Map<String, Integer> sslSummary;
    private List<Integer> vmTotalTrend;
    private List<Integer> vmRunningTrend;
    private List<Integer> vmDeallocatedTrend;
    private Map<String, Integer> vmMachineTypes;

    private List<Integer> sqlTotalTrend;
    private Map<String, Integer> sqlEngines;
    private Map<String, Integer> sqlTiers;
    private Map<String, Integer> sqlHaTypes;

    private Map<String, Integer> storageSummary;
    private Map<String, Integer> bucketSecurity;

    private Map<String, Integer> lbSummary;
    private Map<String, Integer> ipSummary;
    private Map<String, Integer> fwSummary;
    private Map<String, Integer> vpnSummary;
    private Map<String, Integer> vmSummary;
    private Map<String, Integer> cloudRunSummary;

    private List<Integer> vpcTrend;
    private List<Integer> vpcSubnetTrend;
    private List<Integer> lbTrend;
    private List<Integer> gkeNodeTrend;
    private List<Integer> serverlessTrend;
    private List<Integer> diskTrend;
    private List<Integer> snapshotTrend;

    private List<CudCommitmentDto> commitments;
    private List<WorkLogDto> workLogs;

    // Custom Editable Texts
    private String customComments;
    private String customRecommendations;
    private String customSupportLogs;
    private String customWorkLogs;

    private String customRecSecurity;
    private String customRecCost;
    private String customRecPerformance;

    private String customExecSecurity;
    private String customExecCost;
    private String customExecPerformance;

    private boolean isQuarterly;
    private String targetYearMonth;
    private List<String> displayMonths;
    private Map<String, Object> sqlSummary;

    // GCP Active Assist Recommender Full Lists
    private List<String> recSecurity;
    private List<String> recCost;
    private List<String> recPerformance;
    private List<String> recommendationsSecurityList;
    private List<String> recommendationsCostList;
    private List<String> recommendationsPerformanceList;

    // Report Generation Performance Metric
    private Double generationDurationSeconds;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CudCommitmentDto {
        private String name;
        private String category;
        private String region;
        private String startDate;
        private String expiryDate;
        private String status;
        private int dday;
        private String resourceDetail;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WorkLogDto {
        private String category;
        private String target;
        private String workDate;
        private String content;
    }
}
