package com.example.infra.service;

import com.example.infra.dto.MonthlyReportDto;
import com.example.infra.dto.MonthlyReportDto.CudCommitmentDto;
import com.example.infra.dto.MonthlyReportDto.WorkLogDto;
import com.google.cloud.bigquery.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyReportService {

    private final BigQuery bigQuery;
    private final com.example.infra.repository.InfraCustomerRepository customerRepository;
    private final GcpRecommenderService gcpRecommenderService;
    private final VertexAiGeminiService vertexAiGeminiService;

    @Value("${spring.cloud.gcp.project-id:mzc-gcp-managed}")
    private String targetProjectId;

    @Value("${spring.cloud.gcp.bigquery.dataset:infra_admin_dataset}")
    private String datasetName;

    // In-memory store for custom text edits (keyed by projectId_yearMonth)
    private final Map<String, Map<String, String>> customTextStore = new ConcurrentHashMap<>();

    // In-memory cache for generated reports (TTL 30 minutes)
    private final Map<String, CachedReport> reportCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private static class CachedReport {
        final MonthlyReportDto dto;
        final long timestamp;
        CachedReport(MonthlyReportDto dto, long timestamp) {
            this.dto = dto;
            this.timestamp = timestamp;
        }
    }

    private volatile String cachedDatasetLocation = null;

    private String getDatasetLocation() {
        if (cachedDatasetLocation != null) return cachedDatasetLocation;
        try {
            Dataset dataset = bigQuery.getDataset(DatasetId.of(targetProjectId, datasetName));
            if (dataset != null && dataset.getLocation() != null) {
                cachedDatasetLocation = dataset.getLocation();
                log.info("Detected BigQuery dataset '{}.{}' location: {}", targetProjectId, datasetName, cachedDatasetLocation);
                return cachedDatasetLocation;
            }
        } catch (Exception e) {
            log.debug("Dataset metadata lookup skipped: {}", e.getMessage());
        }
        return null;
    }

    private TableResult queryWithFallback(String projectId, String tableName, String selectFields, String whereClause, Map<String, QueryParameterValue> params) {
        String sql = String.format("SELECT %s FROM `%s.%s.%s` %s", selectFields, targetProjectId, datasetName, tableName, whereClause);

        String primaryLoc = getDatasetLocation();
        if (primaryLoc != null && !primaryLoc.isEmpty()) {
            try {
                QueryJobConfiguration.Builder b = QueryJobConfiguration.newBuilder(sql);
                if (params != null) params.forEach(b::addNamedParameter);
                JobId jobId = JobId.newBuilder().setProject(targetProjectId).setLocation(primaryLoc).build();
                return bigQuery.query(b.build(), jobId);
            } catch (Exception e) {
                log.debug("Direct location {} failed for table {}: {}", primaryLoc, tableName, e.getMessage());
            }
        }

        List<String> locationsToTry = Arrays.asList("asia-northeast3", "asia-northeast1", "US", "asia-east1");
        for (String loc : locationsToTry) {
            if (loc.equalsIgnoreCase(primaryLoc)) continue;
            try {
                QueryJobConfiguration.Builder b = QueryJobConfiguration.newBuilder(sql);
                if (params != null) params.forEach(b::addNamedParameter);
                JobId jobId = JobId.newBuilder().setProject(targetProjectId).setLocation(loc).build();
                TableResult result = bigQuery.query(b.build(), jobId);
                cachedDatasetLocation = loc;
                return result;
            } catch (Exception ignored) {
            }
        }

        try {
            QueryJobConfiguration.Builder b = QueryJobConfiguration.newBuilder(sql);
            if (params != null) params.forEach(b::addNamedParameter);
            return bigQuery.query(b.build());
        } catch (Exception e) {
            log.debug("BigQuery query not found for table {}.{}.{} (project {}): {}", targetProjectId, datasetName, tableName, projectId, e.getMessage());
            return null;
        }
    }

    public MonthlyReportDto generateMonthlyReport(String customerName, String projectId, String targetYearMonth) {
        long startTime = System.currentTimeMillis();
        log.info("[REPORT-PERF] ① 요청 수신: customer={}, project={}, yearMonth={}", customerName, projectId, targetYearMonth);
        
        if (targetYearMonth == null || targetYearMonth.isEmpty()) {
            targetYearMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }

        String cacheKey = (projectId != null ? projectId : "unknown") + "_" + targetYearMonth;
        CachedReport cached = reportCache.get(cacheKey);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS)) {
            log.info("[REPORT-PERF] ⚡ 캐시 적중 (Cache Hit): project={}, yearMonth={}, 즉시 반환 (소요시간: {}ms)", 
                    projectId, targetYearMonth, System.currentTimeMillis() - startTime);
            return cached.dto;
        }

        LocalDate targetDate;
        LocalDate reportStartDate;
        LocalDate reportEndDate;
        String displayReportMonth;
        String displayReportPeriod;
        String snapshotYm;

        com.example.infra.entity.InfraCustomer customer = null;
        try {
            List<com.example.infra.entity.InfraCustomer> customers = customerRepository.findAll();
            if (projectId != null && !projectId.trim().isEmpty()) {
                for (com.example.infra.entity.InfraCustomer c : customers) {
                    if (c.getEnvironments() != null) {
                        for (var env : c.getEnvironments()) {
                            if (env.getProjects() != null) {
                                for (var p : env.getProjects()) {
                                    if (projectId.equalsIgnoreCase(p.getProjectId())) {
                                        customer = c;
                                        break;
                                    }
                                }
                            }
                            if (customer != null) break;
                        }
                    }
                    if (customer != null) break;
                }
            }
            if (customer == null && customerName != null && !customerName.trim().isEmpty()) {
                for (com.example.infra.entity.InfraCustomer c : customers) {
                    if (customerName.equalsIgnoreCase(c.getName())) {
                        customer = c;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Customer lookup failed: {}", e.getMessage());
        }

        boolean isQuarterly = false;
        String jiraProjectKey = null;
        if (customer != null) {
            if (customerName == null || customerName.isEmpty() || "MegazoneCloud Customer".equals(customerName)) {
                customerName = customer.getName();
            }
            if (customer.getReportFrequency() != null) {
                String freq = customer.getReportFrequency();
                if (freq.contains("분기") || freq.toLowerCase().contains("quarter") || freq.equalsIgnoreCase("QUARTERLY")) {
                    isQuarterly = true;
                }
            }
            jiraProjectKey = customer.getJiraProjectKeyGcp();
        }

        try {
            targetDate = LocalDate.parse(targetYearMonth + "-01");
            snapshotYm = targetYearMonth;
        } catch (Exception e) {
            targetDate = LocalDate.now();
            snapshotYm = targetDate.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }

        if (isQuarterly) {
            reportStartDate = targetDate.minusMonths(2).withDayOfMonth(1);
            reportEndDate = targetDate.withDayOfMonth(targetDate.lengthOfMonth());
            displayReportMonth = String.format("%d년 %02d월", targetDate.getYear(), targetDate.getMonthValue());
            displayReportPeriod = String.format("%s ~ %s", reportStartDate, reportEndDate);
        } else {
            reportStartDate = targetDate.withDayOfMonth(1);
            reportEndDate = targetDate.withDayOfMonth(targetDate.lengthOfMonth());
            displayReportMonth = String.format("%d년 %02d월", targetDate.getYear(), targetDate.getMonthValue());
            displayReportPeriod = String.format("%s ~ %s", reportStartDate, reportEndDate);
        }

        List<String> fullYearMonths = new ArrayList<>();
        List<String> displayMonths = new ArrayList<>();

        for (int i = 3; i >= 0; i--) {
            LocalDate d = targetDate.minusMonths(i);
            fullYearMonths.add(d.format(DateTimeFormatter.ofPattern("yyyy-MM")));
            displayMonths.add(d.format(DateTimeFormatter.ofPattern("yy.MM")));
        }

        // =========================================================================
        // [최적화 1단계] BigQuery 5개 독립 쿼리 병렬 비동기 실행 (CompletableFuture)
        // =========================================================================
        long bqStartTime = System.currentTimeMillis();
        log.info("[REPORT-PERF] ② BigQuery 병렬 비동기 조회 시작: project={}, yearMonths={}", projectId, fullYearMonths);

        final String finalProjectId = projectId;
        final String finalSnapshotYm = snapshotYm;
        final String finalJiraKey = jiraProjectKey;
        final List<String> finalYearMonths = fullYearMonths;

        CompletableFuture<Map<String, Map<String, Integer>>> assetsFuture = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            var res = fetchMonthlyAssetsIntegrated(finalProjectId, finalYearMonths);
            log.info("[REPORT-PERF] ③ BigQuery (Assets 통합): {}ms", System.currentTimeMillis() - t);
            return res;
        });

        CompletableFuture<List<CudCommitmentDto>> cudFuture = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            var res = fetchCudCommitments(finalProjectId);
            log.info("[REPORT-PERF] ③ BigQuery (CUD Commitments): {}ms", System.currentTimeMillis() - t);
            return res;
        });

        CompletableFuture<Map<String, List<String>>> recommendersFuture = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            var res = fetchAllDailyRecommenders(finalProjectId, finalSnapshotYm);
            log.info("[REPORT-PERF] ③ BigQuery (Recommenders 통합): {}ms", System.currentTimeMillis() - t);
            return res;
        });

        final String finalStartDateStr = reportStartDate.format(DateTimeFormatter.ISO_DATE);
        final String finalEndDateStr = reportEndDate.format(DateTimeFormatter.ISO_DATE);

        CompletableFuture<List<WorkLogDto>> jiraFuture = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            var res = fetchJiraIssues(finalJiraKey, finalStartDateStr, finalEndDateStr);
            log.info("[REPORT-PERF] ③ BigQuery (Jira Issues): {}ms", System.currentTimeMillis() - t);
            return res;
        });

        CompletableFuture<String> snapshotTimeFuture = CompletableFuture.supplyAsync(() -> {
            long t = System.currentTimeMillis();
            var res = fetchLatestSnapshotTime(finalProjectId, finalSnapshotYm);
            log.info("[REPORT-PERF] ③ BigQuery (Snapshot Time): {}ms", System.currentTimeMillis() - t);
            return res;
        });

        // 5개 쿼리 병렬 대기
        CompletableFuture.allOf(assetsFuture, cudFuture, recommendersFuture, jiraFuture, snapshotTimeFuture).join();

        Map<String, Map<String, Integer>> monthlyAssets;
        List<CudCommitmentDto> commitments;
        Map<String, List<String>> allRecommenders;
        List<WorkLogDto> workLogs;
        String snapshotTime;

        try {
            monthlyAssets = assetsFuture.get();
            commitments = cudFuture.get();
            allRecommenders = recommendersFuture.get();
            workLogs = jiraFuture.get();
            snapshotTime = snapshotTimeFuture.get();
        } catch (Exception e) {
            log.warn("[REPORT-PERF] Error retrieving parallel query results: {}", e.getMessage());
            monthlyAssets = Collections.emptyMap();
            commitments = Collections.emptyList();
            allRecommenders = Collections.emptyMap();
            workLogs = Collections.emptyList();
            snapshotTime = null;
        }

        long bqTotalDuration = System.currentTimeMillis() - bqStartTime;
        log.info("[REPORT-PERF] ④ BigQuery 전체 병렬 조회 완료 (총 소요시간: {}ms)", bqTotalDuration);

        // =========================================================================
        // [데이터 매핑 및 가공]
        // =========================================================================
        long mapStart = System.currentTimeMillis();

        Map<String, List<Integer>> iamSummary = new LinkedHashMap<>();
        iamSummary.put("User (사용자)", getTrendList(monthlyAssets, fullYearMonths, "IAM_User"));
        iamSummary.put("Group (그룹)", getTrendList(monthlyAssets, fullYearMonths, "IAM_Group"));
        iamSummary.put("Google Service Account", getTrendList(monthlyAssets, fullYearMonths, "IAM_GoogleSA"));
        iamSummary.put("User Service Account", getTrendList(monthlyAssets, fullYearMonths, "IAM_UserSA"));

        Map<String, Integer> saSecurity = new HashMap<>();
        saSecurity.put("keyOver90", getLatestValue(monthlyAssets, fullYearMonths, "IAM_SA_Key_Over90"));
        saSecurity.put("keyUnder90", getLatestValue(monthlyAssets, fullYearMonths, "IAM_SA_Key_Under90"));
        saSecurity.put("singleKey", getLatestValue(monthlyAssets, fullYearMonths, "IAM_SA_Single"));
        saSecurity.put("multipleKey", getLatestValue(monthlyAssets, fullYearMonths, "IAM_SA_Multiple"));
        saSecurity.put("disabled", getLatestValue(monthlyAssets, fullYearMonths, "IAM_SA_Key_NotUsed"));

        // IAM Owner 권한 계정 수 (서비스 계정 / 사용자 계정)
        int ownerSaCount = getLatestValue(monthlyAssets, fullYearMonths, "IAM_Owner_ServiceAccount");
        int ownerUserCount = getLatestValue(monthlyAssets, fullYearMonths, "IAM_Owner_User");

        Map<String, Integer> sslSummary = new HashMap<>();
        sslSummary.put("googleManaged", getLatestValue(monthlyAssets, fullYearMonths, "SSL_GoogleManaged"));
        sslSummary.put("selfManaged", getLatestValue(monthlyAssets, fullYearMonths, "SSL_SelfManaged"));
        sslSummary.put("expired", getLatestValue(monthlyAssets, fullYearMonths, "SSL_Expired"));
        sslSummary.put("notExpired", getLatestValue(monthlyAssets, fullYearMonths, "SSL_NotExpired"));

        List<Integer> vmTotalTrend = getTrendList(monthlyAssets, fullYearMonths, "VM");
        List<Integer> vmRunningTrend = getTrendList(monthlyAssets, fullYearMonths, "VM_State_Running");
        List<Integer> vmDeallocatedTrend = getTrendList(monthlyAssets, fullYearMonths, "VM_State_Deallocated");
        Map<String, Integer> vmMachineTypes = getPrefixLatestValues(monthlyAssets, fullYearMonths, "VM_Type_");

        List<Integer> sqlTotalTrend = getTrendList(monthlyAssets, fullYearMonths, "SQL_Instance_Type_Primary");
        Map<String, Integer> sqlEngines = getPrefixLatestValues(monthlyAssets, fullYearMonths, "SQL_Engine_");
        Map<String, Integer> sqlTiers = getPrefixLatestValues(monthlyAssets, fullYearMonths, "SQL_Machine_Type_");
        Map<String, Integer> sqlHaTypes = new HashMap<>();
        sqlHaTypes.put("Regional (HA)", getLatestValue(monthlyAssets, fullYearMonths, "SQL_Availability_Regional"));
        sqlHaTypes.put("Zonal (Single)", getLatestValue(monthlyAssets, fullYearMonths, "SQL_Availability_Zonal"));

        Map<String, Integer> storageSummary = new HashMap<>();
        storageSummary.put("diskTotal", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Disk_Total"));
        storageSummary.put("diskIdle", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Disk_Idle"));
        storageSummary.put("snapshotTotal", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Snapshot_Total"));
        storageSummary.put("imageTotal", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Image_Total"));

        Map<String, Integer> bucketSecurity = new HashMap<>();
        bucketSecurity.put("total", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Bucket_Total"));
        bucketSecurity.put("acl", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Security_ACL"));
        bucketSecurity.put("notPublic", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Security_NotPublic"));
        bucketSecurity.put("lifecycleEnabled", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Security_LifecycleEnabled"));
        bucketSecurity.put("lifecycleDisabled", getLatestValue(monthlyAssets, fullYearMonths, "Storage_Security_LifecycleDisabled"));

        Map<String, Integer> lbSummary = new HashMap<>();
        lbSummary.put("total", getLatestValue(monthlyAssets, fullYearMonths, "LoadBalancer"));
        lbSummary.put("application", getLatestValue(monthlyAssets, fullYearMonths, "LB_Type_Application"));
        lbSummary.put("network", getLatestValue(monthlyAssets, fullYearMonths, "LB_Type_Network"));
        lbSummary.put("external", getLatestValue(monthlyAssets, fullYearMonths, "LB_Access_External"));
        lbSummary.put("internal", getLatestValue(monthlyAssets, fullYearMonths, "LB_Access_Internal"));
        lbSummary.put("healthUnhealthyTotal", getLatestValue(monthlyAssets, fullYearMonths, "LB_Health_Unhealthy_Total"));
        lbSummary.put("healthHealthyTotal", getLatestValue(monthlyAssets, fullYearMonths, "LB_Health_Healthy_Total"));
        lbSummary.put("http500Last30Days", getLatestValue(monthlyAssets, fullYearMonths, "LB_HTTP_500_30D_Total"));

        Map<String, Integer> ipSummary = new HashMap<>();
        int extUsed = getLatestValue(monthlyAssets, fullYearMonths, "IP_Static_Used");
        if (extUsed == 0) extUsed = getLatestValue(monthlyAssets, fullYearMonths, "IP_External_Used");
        int extTotal = getLatestValue(monthlyAssets, fullYearMonths, "IP_Static_Total");
        int extUnused = extTotal > 0 ? Math.max(0, extTotal - extUsed) : getLatestValue(monthlyAssets, fullYearMonths, "IP_External_Unused");
        ipSummary.put("externalUsed", extUsed);
        ipSummary.put("externalUnused", extUnused);

        Map<String, Integer> fwSummary = new HashMap<>();
        int totalRules = getLatestValue(monthlyAssets, fullYearMonths, "FW_Total_Rules");
        int loggingEnabled = getLatestValue(monthlyAssets, fullYearMonths, "FW_Log_Enabled_Rules");
        if (loggingEnabled == 0) loggingEnabled = getLatestValue(monthlyAssets, fullYearMonths, "FW_Log_Enabled");
        if (loggingEnabled == 0) loggingEnabled = getLatestValue(monthlyAssets, fullYearMonths, "FW_Used");
        if (totalRules == 0) totalRules = loggingEnabled + getLatestValue(monthlyAssets, fullYearMonths, "FW_Unused");
        fwSummary.put("totalRules", totalRules);
        fwSummary.put("loggingEnabled", loggingEnabled);
        fwSummary.put("ingressAllow", getLatestValue(monthlyAssets, fullYearMonths, "FW_Ingress_Allow"));
        fwSummary.put("egressAllow", getLatestValue(monthlyAssets, fullYearMonths, "FW_Egress_Allow"));
        fwSummary.put("logEnabled", loggingEnabled);

        Map<String, Integer> vpnSummary = new HashMap<>();
        int vpnConn = getLatestValue(monthlyAssets, fullYearMonths, "VPN_Tunnel_State_Connected");
        int vpnDisconn = getLatestValue(monthlyAssets, fullYearMonths, "VPN_Tunnel_State_Disconnected");
        if (vpnConn == 0 && vpnDisconn == 0) vpnConn = getLatestValue(monthlyAssets, fullYearMonths, "VPN_Connected_Tunnels");
        int vpnTot = vpnConn + vpnDisconn;
        vpnSummary.put("connectedTunnels", vpnConn);
        vpnSummary.put("disconnectedTunnels", vpnDisconn);
        vpnSummary.put("totalTunnels", vpnTot);

        Map<String, Integer> cloudRunSummary = new HashMap<>();
        int crServices = getLatestValue(monthlyAssets, fullYearMonths, "CloudRun_Svc_Total");
        if (crServices == 0) crServices = getLatestValue(monthlyAssets, fullYearMonths, "CloudRun_Services");
        cloudRunSummary.put("totalServices", crServices);
        cloudRunSummary.put("ingressAll", getLatestValue(monthlyAssets, fullYearMonths, "CloudRun_Svc_Ingress_All"));
        cloudRunSummary.put("ingressInternal", getLatestValue(monthlyAssets, fullYearMonths, "CloudRun_Svc_Ingress_Internal"));

        Map<String, Integer> vmSummary = new HashMap<>();
        int vmRunning = getLatestValue(monthlyAssets, fullYearMonths, "VM_State_Running");
        int vmDeallocated = getLatestValue(monthlyAssets, fullYearMonths, "VM_State_Deallocated");
        int vmTot = vmTotalTrend.size() >= 4 ? vmTotalTrend.get(3) : (vmRunning + vmDeallocated);
        if (vmRunning == 0 && vmDeallocated == 0 && vmTot > 0) vmRunning = vmTot;
        vmSummary.put("running", vmRunning);
        vmSummary.put("stopped", vmDeallocated);
        vmSummary.put("total", vmTot);

        int vmTotal = vmTotalTrend.size() >= 4 ? vmTotalTrend.get(3) : 0;
        int vmTotalDelta = vmTotalTrend.size() >= 4 ? (vmTotalTrend.get(3) - vmTotalTrend.get(2)) : 0;
        int sqlTotal = sqlTotalTrend.size() >= 4 ? sqlTotalTrend.get(3) : 0;
        int sqlTotalDelta = sqlTotalTrend.size() >= 4 ? (sqlTotalTrend.get(3) - sqlTotalTrend.get(2)) : 0;

        List<Integer> diskTotalTrend = getTrendList(monthlyAssets, fullYearMonths, "Storage_Disk_Total");
        int diskTotal = diskTotalTrend.size() >= 4 ? diskTotalTrend.get(3) : 0;
        int diskTotalDelta = diskTotalTrend.size() >= 4 ? (diskTotalTrend.get(3) - diskTotalTrend.get(2)) : 0;

        List<Integer> bucketTotalTrend = getTrendList(monthlyAssets, fullYearMonths, "Storage_Bucket_Total");
        int bucketTotal = bucketTotalTrend.size() >= 4 ? bucketTotalTrend.get(3) : 0;
        int bucketTotalDelta = bucketTotalTrend.size() >= 4 ? (bucketTotalTrend.get(3) - bucketTotalTrend.get(2)) : 0;

        List<Integer> gkeTotalTrend = getTrendList(monthlyAssets, fullYearMonths, "GKE_Cluster_Total");
        if (gkeTotalTrend.stream().allMatch(v -> v == 0)) {
            gkeTotalTrend = getTrendList(monthlyAssets, fullYearMonths, "GKE_Cluster");
        }
        int gkeTotal = gkeTotalTrend.size() >= 4 ? gkeTotalTrend.get(3) : 0;
        int gkeTotalDelta = gkeTotalTrend.size() >= 4 ? (gkeTotalTrend.get(3) - gkeTotalTrend.get(2)) : 0;

        List<Integer> lbTotalTrend = getTrendList(monthlyAssets, fullYearMonths, "LoadBalancer");
        int lbTotal = lbTotalTrend.size() >= 4 ? lbTotalTrend.get(3) : 0;
        int lbTotalDelta = lbTotalTrend.size() >= 4 ? (lbTotalTrend.get(3) - lbTotalTrend.get(2)) : 0;

        // Custom text overrides
        String storeKey = projectId + "_" + targetYearMonth;
        Map<String, String> storedText = customTextStore.getOrDefault(storeKey, new HashMap<>());

        // Recommender Lists
        List<String> bqRecSecurity = allRecommenders.getOrDefault("SECURITY", Collections.emptyList());
        List<String> bqRecCost = allRecommenders.getOrDefault("COST", Collections.emptyList());
        List<String> bqRecPerformance = new ArrayList<>(allRecommenders.getOrDefault("PERFORMANCE", Collections.emptyList()));
        for (String r : allRecommenders.getOrDefault("RELIABILITY", Collections.emptyList())) {
            if (!bqRecPerformance.contains(r)) bqRecPerformance.add(r);
        }

        List<String> recSecurity = new ArrayList<>(bqRecSecurity);
        List<String> recCost = new ArrayList<>(bqRecCost);
        List<String> recPerformance = new ArrayList<>(bqRecPerformance);

        if (storedText.containsKey("customRecSecurity") && !storedText.get("customRecSecurity").isEmpty()) {
            recSecurity = Arrays.asList(storedText.get("customRecSecurity").split("\n"));
        }
        if (storedText.containsKey("customRecCost") && !storedText.get("customRecCost").isEmpty()) {
            recCost = Arrays.asList(storedText.get("customRecCost").split("\n"));
        }
        if (storedText.containsKey("customRecPerformance") && !storedText.get("customRecPerformance").isEmpty()) {
            recPerformance = Arrays.asList(storedText.get("customRecPerformance").split("\n"));
        }

        String mspSalesContact = "미지정";
        String mspTechContact = "미지정";
        String mspGrade = "Standard";
        if (customer != null) {
            if (customer.getMspSalesRep() != null && !customer.getMspSalesRep().isEmpty()) mspSalesContact = customer.getMspSalesRep();
            if (customer.getMspRep() != null && !customer.getMspRep().isEmpty()) mspTechContact = customer.getMspRep();
            if (customer.getMspGrade() != null && !customer.getMspGrade().isEmpty()) mspGrade = customer.getMspGrade();
        }
        if (storedText.containsKey("mspSalesContact")) mspSalesContact = storedText.get("mspSalesContact");
        if (storedText.containsKey("mspTechContact")) mspTechContact = storedText.get("mspTechContact");
        if (storedText.containsKey("mspGrade")) mspGrade = storedText.get("mspGrade");

        log.info("[REPORT-PERF] ⑤ 데이터 매핑 및 Recommendation 그룹핑 완료 ({}ms)", System.currentTimeMillis() - mapStart);

        // =========================================================================
        // [최적화 2단계] Vertex AI 1회 통합 JSON 호출 (보안 + 비용 + 성능 동시 생성)
        // =========================================================================
        Map<String, String> aiSummaries = vertexAiGeminiService.summarizeAllCategoriesIntegrated(recSecurity, recCost, recPerformance);

        String execSecurityText = aiSummaries.getOrDefault("보안 및 컴플라이언스", "");
        String execCostText = aiSummaries.getOrDefault("비용 최적화", "");
        String execPerfText = aiSummaries.getOrDefault("성능 및 안정성", "");

        List<Integer> vpcTrend = getTrendList(monthlyAssets, fullYearMonths, "VPC_Network_Total");
        List<Integer> vpcSubnetTrend = getTrendList(monthlyAssets, fullYearMonths, "VPC_Subnet_Total");
        List<Integer> lbTrend = getTrendList(monthlyAssets, fullYearMonths, "LoadBalancer");
        List<Integer> gkeNodeTrend = getTrendList(monthlyAssets, fullYearMonths, "GKE_Node_Total");
        if (gkeNodeTrend.stream().allMatch(v -> v == 0)) {
            gkeNodeTrend = getTrendList(monthlyAssets, fullYearMonths, "GKE_Cluster_Total");
        }
        List<Integer> serverlessTrend = getTrendList(monthlyAssets, fullYearMonths, "CloudRun_Svc_Total");
        if (serverlessTrend.stream().allMatch(v -> v == 0)) {
            serverlessTrend = getTrendList(monthlyAssets, fullYearMonths, "CloudRun_Services");
        }
        List<Integer> snapshotTrend = getTrendList(monthlyAssets, fullYearMonths, "Storage_Snapshot_Total");

        // =========================================================================
        // [⑩ 화면 DTO 생성]
        // =========================================================================
        long dtoStart = System.currentTimeMillis();
        MonthlyReportDto result = MonthlyReportDto.builder()
                .customerName(customerName)
                .projectId(projectId)
                .reportMonth(displayReportMonth)
                .reportPeriod(displayReportPeriod)
                .isQuarterly(isQuarterly)
                .targetYearMonth(targetYearMonth)
                .months(displayMonths)
                .displayMonths(displayMonths)
                .mspSalesContact(mspSalesContact)
                .mspTechContact(mspTechContact)
                .mspGrade(mspGrade)
                .iamSummary(iamSummary)
                .saSecurity(saSecurity)
                .ownerSaCount(ownerSaCount)
                .ownerUserCount(ownerUserCount)
                .sslSummary(sslSummary)
                .vmSummary(vmSummary)
                .vmTotalTrend(vmTotalTrend)
                .vmRunningTrend(vmRunningTrend)
                .vmDeallocatedTrend(vmDeallocatedTrend)
                .vmMachineTypes(vmMachineTypes)
                .sqlSummary(Map.of("total", sqlTotal, "ha", sqlHaTypes.getOrDefault("Regional (HA)", 0), "single", sqlHaTypes.getOrDefault("Zonal (Single)", 0)))
                .sqlTotalTrend(sqlTotalTrend)
                .sqlEngines(sqlEngines)
                .sqlTiers(sqlTiers)
                .sqlHaTypes(sqlHaTypes)
                .storageSummary(storageSummary)
                .bucketSecurity(bucketSecurity)
                .lbSummary(lbSummary)
                .ipSummary(ipSummary)
                .fwSummary(fwSummary)
                .vpnSummary(vpnSummary)
                .cloudRunSummary(cloudRunSummary)
                .vmTotal(vmTotal)
                .vmTotalDelta(vmTotalDelta)
                .sqlTotal(sqlTotal)
                .sqlTotalDelta(sqlTotalDelta)
                .diskTotal(diskTotal)
                .diskTotalDelta(diskTotalDelta)
                .bucketTotal(bucketTotal)
                .bucketTotalDelta(bucketTotalDelta)
                .gkeTotal(gkeTotal)
                .gkeTotalDelta(gkeTotalDelta)
                .lbTotal(lbTotal)
                .lbTotalDelta(lbTotalDelta)
                .vpcTrend(vpcTrend)
                .vpcSubnetTrend(vpcSubnetTrend)
                .lbTrend(lbTrend)
                .gkeNodeTrend(gkeNodeTrend)
                .serverlessTrend(serverlessTrend)
                .diskTrend(diskTotalTrend)
                .snapshotTrend(snapshotTrend)
                .commitments(commitments)
                .recSecurity(recSecurity)
                .recCost(recCost)
                .recPerformance(recPerformance)
                .recommendationsSecurityList(recSecurity)
                .recommendationsCostList(recCost)
                .recommendationsPerformanceList(recPerformance)
                .customComments(storedText.getOrDefault("customComments", "• BigQuery에 수집된 GCP Active Assist 실시간 Recommender 분석 결과를 바탕으로 권고 사항을 안내합니다."))
                .customRecommendations(storedText.getOrDefault("customRecommendations", "GCP Active Assist 정기 점검 권고 사항 내역을 확인해 주시기 바랍니다."))
                .customRecSecurity(storedText.getOrDefault("customRecSecurity", String.join("\n", recSecurity)))
                .customRecCost(storedText.getOrDefault("customRecCost", String.join("\n", recCost)))
                .customRecPerformance(storedText.getOrDefault("customRecPerformance", String.join("\n", recPerformance)))
                .customExecSecurity(storedText.getOrDefault("customExecSecurity", execSecurityText))
                .customExecCost(storedText.getOrDefault("customExecCost", execCostText))
                .customExecPerformance(storedText.getOrDefault("customExecPerformance", execPerfText))
                .workLogs(workLogs)
                .snapshotTime(snapshotTime)
                .build();

        long totalDurationMs = System.currentTimeMillis() - startTime;
        double durationSec = Math.round((totalDurationMs / 100.0)) / 10.0;
        result.setGenerationDurationSeconds(durationSec);

        // 캐시에 저장
        reportCache.put(cacheKey, new CachedReport(result, System.currentTimeMillis()));

        log.info("[REPORT-PERF] ⑩ 화면 DTO 생성 완료 ({}ms)", System.currentTimeMillis() - dtoStart);
        log.info("[REPORT-PERF] ⑪ 응답 반환 완료: project={}, 총 소요시간: {}초 ({}ms)", projectId, durationSec, totalDurationMs);

        return result;
    }

    public void saveCustomText(String projectId, String yearMonth, Map<String, String> textMap) {
        String key = projectId + "_" + yearMonth;
        Map<String, String> current = customTextStore.computeIfAbsent(key, k -> new HashMap<>());
        current.putAll(textMap);
        // 사용자 수동 저장 시 캐시 무효화
        reportCache.remove(key);
    }

    public void saveCustomText(String projectId, String targetYearMonth,
                               String customComments, String customRecommendations, String customSupportLogs,
                               String mspSalesContact, String mspTechContact, String mspGrade,
                               String customRecSecurity, String customRecCost, String customRecPerformance,
                               String customExecSecurity, String customExecCost, String customExecPerformance,
                               String customWorkLogs) {
        Map<String, String> map = new HashMap<>();
        if (customComments != null) map.put("customComments", customComments);
        if (customRecommendations != null) map.put("customRecommendations", customRecommendations);
        if (customSupportLogs != null) map.put("customSupportLogs", customSupportLogs);
        if (mspSalesContact != null) map.put("mspSalesContact", mspSalesContact);
        if (mspTechContact != null) map.put("mspTechContact", mspTechContact);
        if (mspGrade != null) map.put("mspGrade", mspGrade);
        if (customRecSecurity != null) map.put("customRecSecurity", customRecSecurity);
        if (customRecCost != null) map.put("customRecCost", customRecCost);
        if (customRecPerformance != null) map.put("customRecPerformance", customRecPerformance);
        if (customExecSecurity != null) map.put("customExecSecurity", customExecSecurity);
        if (customExecCost != null) map.put("customExecCost", customExecCost);
        if (customExecPerformance != null) map.put("customExecPerformance", customExecPerformance);
        if (customWorkLogs != null) map.put("customWorkLogs", customWorkLogs);

        saveCustomText(projectId, targetYearMonth, map);
    }

    // =========================================================================
    // [BigQuery 최적화 헬퍼 메서드들]
    // =========================================================================

    /**
     * [최적화] 4개월치 자산 데이터를 단 1개의 BigQuery 쿼리로 통합 조회
     */
    private Map<String, Map<String, Integer>> fetchMonthlyAssetsIntegrated(String projectId, List<String> yearMonths) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        for (String ym : yearMonths) {
            result.put(ym, new HashMap<>());
        }
        if (yearMonths.isEmpty()) return result;

        // VM_Type_* 는 VM/VM_State_* 와 동일한 snapshot_date 기준으로만 유효 -> 자원 유형별 출처 스냅샷 날짜 추적
        Map<String, Map<String, String>> typeSourceDate = new HashMap<>();

        String selectFields = "SUBSTR(CAST(snapshot_date AS STRING), 1, 7) as ym, resource_type, resource_count, CAST(snapshot_date AS STRING) as sd";
        String whereClause = "WHERE project_id = @projectId AND SUBSTR(CAST(snapshot_date AS STRING), 1, 7) IN UNNEST(@ymList) " +
                "QUALIFY ROW_NUMBER() OVER(PARTITION BY SUBSTR(CAST(snapshot_date AS STRING), 1, 7), resource_type ORDER BY snapshot_date DESC) = 1";

        Map<String, QueryParameterValue> params = new HashMap<>();
        params.put("projectId", QueryParameterValue.string(projectId));
        params.put("ymList", QueryParameterValue.array(yearMonths.toArray(new String[0]), StandardSQLTypeName.STRING));

        try {
            TableResult tableResult = queryWithFallback(projectId, "daily_asset_inventory", selectFields, whereClause, params);
            if (tableResult != null) {
                for (FieldValueList row : tableResult.iterateAll()) {
                    String ym = row.get("ym").getStringValue();
                    String assetType = row.get("resource_type").getStringValue();
                    int count = (int) row.get("resource_count").getLongValue();
                    String sd = null;
                    try {
                        if (!row.get("sd").isNull()) sd = row.get("sd").getStringValue();
                    } catch (Exception ignored) {}
                    if (result.containsKey(ym)) {
                        result.get(ym).put(assetType, count);
                        if (assetType.startsWith("VM_Type_") || "VM".equals(assetType)
                                || "VM_State_Running".equals(assetType) || "VM_State_Deallocated".equals(assetType)) {
                            typeSourceDate.computeIfAbsent(ym, k -> new HashMap<>()).put(assetType, sd);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Integrated assets query fallback to per-month query: {}", e.getMessage());
            return fetchMonthlyAssetsLegacy(projectId, yearMonths);
        }

        // count=0 이 되어 당일 미기록된 VM_Type_* (이전 스냅샷의 잔여 양수값) 를 권위 VM 스냅샷 날짜 기준으로 정리
        // -> sum(VM_Type_*) 이 해당 월 VM 총수와 일치하도록 보정 (머신 유형 텍스트 산술 오류 근본 원인 수정)
        for (String ym : result.keySet()) {
            Map<String, String> src = typeSourceDate.get(ym);
            if (src == null) continue;
            String vmDate = src.get("VM");
            if (vmDate == null) vmDate = src.get("VM_State_Running");
            if (vmDate == null) vmDate = src.get("VM_State_Deallocated");
            if (vmDate == null) continue;
            Map<String, Integer> monthMap = result.get(ym);
            List<String> staleTypes = new ArrayList<>();
            for (Map.Entry<String, Integer> e : monthMap.entrySet()) {
                if (!e.getKey().startsWith("VM_Type_")) continue;
                String typeDate = src.get(e.getKey());
                if (typeDate == null || !typeDate.equals(vmDate)) {
                    staleTypes.add(e.getKey());
                }
            }
            for (String t : staleTypes) {
                monthMap.remove(t);
            }
        }
        return result;
    }

    private Map<String, Map<String, Integer>> fetchMonthlyAssetsLegacy(String projectId, List<String> yearMonths) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        for (String ym : yearMonths) result.put(ym, new HashMap<>());
        String selectFields = "resource_type, resource_count";
        String whereClause = "WHERE project_id = @projectId AND CAST(snapshot_date AS STRING) = (" +
                String.format("  SELECT MAX(CAST(snapshot_date AS STRING)) FROM `%s.%s.daily_asset_inventory` ", targetProjectId, datasetName) +
                "  WHERE project_id = @projectId AND STARTS_WITH(CAST(snapshot_date AS STRING), @yearMonthPrefix)" +
                ")";
        for (String ym : yearMonths) {
            Map<String, QueryParameterValue> params = new HashMap<>();
            params.put("projectId", QueryParameterValue.string(projectId));
            params.put("yearMonthPrefix", QueryParameterValue.string(ym));
            TableResult tableResult = queryWithFallback(projectId, "daily_asset_inventory", selectFields, whereClause, params);
            if (tableResult != null) {
                Map<String, Integer> assetMap = result.get(ym);
                for (FieldValueList row : tableResult.iterateAll()) {
                    assetMap.put(row.get("resource_type").getStringValue(), (int) row.get("resource_count").getLongValue());
                }
            }
        }
        return result;
    }

    /**
     * [최적화] 모든 카테고리의 Recommender를 단 1개의 BigQuery 쿼리로 통합 조회 및 한글 변환
     */
    private Map<String, List<String>> fetchAllDailyRecommenders(String projectId, String yearMonthPrefix) {
        Map<String, List<String>> map = new HashMap<>();
        map.put("SECURITY", new ArrayList<>());
        map.put("COST", new ArrayList<>());
        map.put("PERFORMANCE", new ArrayList<>());
        map.put("RELIABILITY", new ArrayList<>());

        String selectFields = "category, priority, recommendation_description";
        String whereClause = "WHERE project_id = @projectId AND STARTS_WITH(CAST(snapshot_date AS STRING), @yearMonthPrefix) " +
                "ORDER BY CASE priority WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 WHEN 'LOW' THEN 4 ELSE 5 END, snapshot_date DESC";

        Map<String, QueryParameterValue> params = new HashMap<>();
        params.put("projectId", QueryParameterValue.string(projectId));
        params.put("yearMonthPrefix", QueryParameterValue.string(yearMonthPrefix));

        try {
            TableResult tableResult = queryWithFallback(projectId, "daily_recommender_inventory", selectFields, whereClause, params);
            if (tableResult != null) {
                for (FieldValueList row : tableResult.iterateAll()) {
                    String cat = row.get("category").getStringValue();
                    String desc = row.get("recommendation_description").getStringValue();
                    String prio = "MEDIUM";
                    try {
                        if (!row.get("priority").isNull()) prio = row.get("priority").getStringValue();
                    } catch (Exception ignored) {}

                    if (desc != null && !desc.isEmpty() && map.containsKey(cat)) {
                        String koreanDesc = gcpRecommenderService.translateRecommendationToKorean(desc);
                        String targetRes = GcpRecommenderService.extractTargetFromDescription(desc);
                        if (targetRes.isEmpty()) {
                            targetRes = GcpRecommenderService.extractTargetFromDescription(koreanDesc);
                        }

                        String formatted = GcpRecommenderService.formatRecommendationText(prio, targetRes, koreanDesc);
                        List<String> list = map.get(cat);
                        if (!list.contains(formatted) && !list.contains(koreanDesc) && !list.contains(desc)) {
                            list.add(formatted);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("All recommenders query skipped: {}", e.getMessage());
        }
        return map;
    }

    private List<WorkLogDto> fetchJiraIssues(String jiraProjectKey, String startDateStr, String endDateStr) {
        List<WorkLogDto> list = new ArrayList<>();
        if (jiraProjectKey == null || jiraProjectKey.trim().isEmpty() 
                || "null".equalsIgnoreCase(jiraProjectKey.trim()) 
                || "미지정".equalsIgnoreCase(jiraProjectKey.trim())
                || "none".equalsIgnoreCase(jiraProjectKey.trim())) {
            log.info("[JIRA-FETCH] Jira project key is empty: '{}'", jiraProjectKey);
            return list;
        }

        log.info("[JIRA-FETCH] Querying Jira for key: {}, report period: {} ~ {}", jiraProjectKey, startDateStr, endDateStr);

        String[][] candidates = new String[][]{
                {"jira_issue_inventory", "project_key"},
                {"jira_issue_inventory", "jira_project_key"},
                {"daily_jira_issue_inventory", "project_key"},
                {"daily_jira_issue_inventory", "jira_project_key"}
        };

        Set<String> seen = new HashSet<>();

        for (String[] cand : candidates) {
            String tbl = cand[0];
            String col = cand[1];
            String sql = String.format(
                    "SELECT issue_key, summary, work_category, issue_type, report_yn, " +
                    "       CAST(created_at AS STRING) as created_at, CAST(snapshot_date AS STRING) as snapshot_date " +
                    "FROM `%s.%s.%s` " +
                    "WHERE UPPER(%s) = UPPER(@jiraKey) " +
                    "  AND (report_yn IS NULL OR UPPER(TRIM(CAST(report_yn AS STRING))) NOT IN ('NO', 'N')) " +
                    "  AND (issue_type IS NULL OR (" +
                    "       TRIM(CAST(issue_type AS STRING)) NOT IN ('하위작업', '하위 작업', 'Sub-task', 'Subtask') " +
                    "       AND UPPER(TRIM(CAST(issue_type AS STRING))) NOT IN ('SUB-TASK', 'SUBTASK', 'SUB_TASK')" +
                    "  )) " +
                    "  AND ( " +
                    "       SUBSTR(CAST(created_at AS STRING), 1, 10) BETWEEN @startDate AND @endDate " +
                    "       OR (created_at IS NULL AND SUBSTR(CAST(snapshot_date AS STRING), 1, 10) BETWEEN @startDate AND @endDate) " +
                    "  ) " +
                    "ORDER BY created_at DESC",
                    targetProjectId, datasetName, tbl, col);

            try {
                QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(sql)
                        .addNamedParameter("jiraKey", QueryParameterValue.string(jiraProjectKey))
                        .addNamedParameter("startDate", QueryParameterValue.string(startDateStr))
                        .addNamedParameter("endDate", QueryParameterValue.string(endDateStr))
                        .build();
                TableResult tableResult = bigQuery.query(queryConfig);
                if (tableResult != null && tableResult.iterateAll().iterator().hasNext()) {
                    for (FieldValueList row : tableResult.iterateAll()) {
                        String key = row.get("issue_key").isNull() ? "" : row.get("issue_key").getStringValue();
                        if (key.isEmpty() || !seen.add(key)) continue;

                        String rawIssueType = row.get("issue_type").isNull() ? "" : row.get("issue_type").getStringValue();
                        if (isSubtaskType(rawIssueType)) continue;

                        String summary = row.get("summary").isNull() ? "" : row.get("summary").getStringValue();
                        String workCategory = "기술지원";
                        try {
                            if (!row.get("work_category").isNull() && !row.get("work_category").getStringValue().isEmpty()) {
                                workCategory = row.get("work_category").getStringValue();
                            } else if (!rawIssueType.isEmpty()) {
                                workCategory = rawIssueType;
                            }
                        } catch (Exception ignored) {}

                        String createdAt = row.get("created_at").isNull() ? "" : row.get("created_at").getStringValue();
                        String snapDate = row.get("snapshot_date").isNull() ? "" : row.get("snapshot_date").getStringValue();
                        String workDate = !createdAt.isEmpty() ? createdAt.substring(0, Math.min(10, createdAt.length())) : snapDate;

                        list.add(WorkLogDto.builder()
                                .category(workCategory)
                                .target("GCP")
                                .workDate(workDate)
                                .content(summary)
                                .build());
                    }
                    if (!list.isEmpty()) {
                        log.info("[JIRA-FETCH] Successfully loaded {} issues from table {}.{}.{} within period {} ~ {}",
                                list.size(), targetProjectId, datasetName, tbl, startDateStr, endDateStr);
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("[JIRA-FETCH] Candidate {}.{}.{} with column {} skipped: {}", targetProjectId, datasetName, tbl, col, e.getMessage());
            }
        }

        log.info("[JIRA-FETCH] Loaded total {} work logs for key: {} (Period: {} ~ {})",
                list.size(), jiraProjectKey, startDateStr, endDateStr);
        return list;
    }

    /**
     * Jira 이슈 유형이 하위 작업(Sub-task)인지 여부 판별
     */
    public static boolean isSubtaskType(String issueType) {
        if (issueType == null || issueType.trim().isEmpty()) return false;
        String trimmed = issueType.trim();
        String upper = trimmed.toUpperCase();
        return trimmed.equals("하위작업") || trimmed.equals("하위 작업")
                || upper.equals("SUB-TASK") || upper.equals("SUBTASK") || upper.equals("SUB_TASK")
                || upper.contains("SUBTASK") || upper.contains("SUB-TASK");
    }

    private List<CudCommitmentDto> fetchCudCommitments(String projectId) {
        List<CudCommitmentDto> list = new ArrayList<>();
        String selectFields = "commitment_name, category, region, CAST(start_date AS STRING) as start_date, CAST(expiry_date AS STRING) as expiry_date, status, resource_detail";
        String whereClause = "WHERE project_id = @projectId";

        Map<String, QueryParameterValue> params = new HashMap<>();
        params.put("projectId", QueryParameterValue.string(projectId));

        TableResult tableResult = queryWithFallback(projectId, "cud_commitments", selectFields, whereClause, params);
        if (tableResult != null) {
            LocalDate today = LocalDate.now();
            for (FieldValueList row : tableResult.iterateAll()) {
                String expiryStr = row.get("expiry_date").isNull() ? null : row.get("expiry_date").getStringValue();
                long dday = 0;
                if (expiryStr != null && !expiryStr.isEmpty()) {
                    try {
                        LocalDate expiryDate = LocalDate.parse(expiryStr);
                        dday = ChronoUnit.DAYS.between(today, expiryDate);
                    } catch (Exception e) {
                        dday = 0;
                    }
                }

                CudCommitmentDto dto = CudCommitmentDto.builder()
                        .name(row.get("commitment_name").isNull() ? "" : row.get("commitment_name").getStringValue())
                        .category(row.get("category").isNull() ? "" : row.get("category").getStringValue())
                        .region(row.get("region").isNull() ? "" : row.get("region").getStringValue())
                        .startDate(row.get("start_date").isNull() ? "" : row.get("start_date").getStringValue())
                        .expiryDate(expiryStr != null ? expiryStr : "")
                        .status(row.get("status").isNull() ? "ACTIVE" : row.get("status").getStringValue())
                        .dday((int) dday)
                        .resourceDetail(row.get("resource_detail").isNull() ? "" : row.get("resource_detail").getStringValue())
                        .build();

                list.add(dto);
            }
        }
        return list;
    }

    private String fetchLatestSnapshotTime(String projectId, String yearMonth) {
        String selectFields = "MAX(CAST(snapshot_date AS STRING)) as latest_time";
        String whereClause = "WHERE project_id = @projectId AND STARTS_WITH(CAST(snapshot_date AS STRING), @yearMonthPrefix)";

        Map<String, QueryParameterValue> params = new HashMap<>();
        params.put("projectId", QueryParameterValue.string(projectId));
        params.put("yearMonthPrefix", QueryParameterValue.string(yearMonth));

        TableResult tableResult = queryWithFallback(projectId, "daily_asset_inventory", selectFields, whereClause, params);
        if (tableResult != null) {
            for (FieldValueList row : tableResult.iterateAll()) {
                if (!row.get("latest_time").isNull()) {
                    return row.get("latest_time").getStringValue();
                }
            }
        }
        return null;
    }

    private List<Integer> getTrendList(Map<String, Map<String, Integer>> monthlyAssets, List<String> yearMonths, String assetType) {
        List<Integer> list = new ArrayList<>();
        for (String ym : yearMonths) {
            Map<String, Integer> map = monthlyAssets.getOrDefault(ym, Collections.emptyMap());
            int val = map.getOrDefault(assetType, 0);
            if (val == 0 && "VM".equals(assetType)) {
                val = map.getOrDefault("VM_State_Running", 0) + map.getOrDefault("VM_State_Deallocated", 0);
            } else if (val == 0 && "SQL_Instance_Type_Primary".equals(assetType)) {
                val = map.getOrDefault("SQL", map.getOrDefault("CloudSQL", 0));
            }
            list.add(val);
        }
        return list;
    }

    private Integer getLatestValue(Map<String, Map<String, Integer>> monthlyAssets, List<String> yearMonths, String assetType) {
        if (yearMonths.isEmpty()) return 0;
        String latestYm = yearMonths.get(yearMonths.size() - 1);
        Map<String, Integer> map = monthlyAssets.getOrDefault(latestYm, Collections.emptyMap());
        if (map.containsKey(assetType)) return map.get(assetType);

        if ("VM".equals(assetType)) {
            return map.getOrDefault("VM_State_Running", 0) + map.getOrDefault("VM_State_Deallocated", 0);
        } else if ("SQL_Instance_Type_Primary".equals(assetType)) {
            return map.getOrDefault("SQL", map.getOrDefault("CloudSQL", 0));
        } else if ("Storage_Disk_Total".equals(assetType)) {
            return map.getOrDefault("Disk", map.getOrDefault("PD", 0));
        } else if ("LoadBalancer".equals(assetType)) {
            return map.getOrDefault("LB_Total", map.getOrDefault("ForwardingRules", 0));
        } else if ("GKE_Cluster".equals(assetType)) {
            return map.getOrDefault("GKE", 0);
        }
        return map.getOrDefault(assetType, 0);
    }

    private Map<String, Integer> getPrefixLatestValues(Map<String, Map<String, Integer>> monthlyAssets, List<String> yearMonths, String prefix) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (yearMonths.isEmpty()) return result;
        String latestYm = yearMonths.get(yearMonths.size() - 1);
        Map<String, Integer> map = monthlyAssets.getOrDefault(latestYm, Collections.emptyMap());

        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String cleanKey = entry.getKey().substring(prefix.length());
                result.put(cleanKey, entry.getValue());
            }
        }
        return result;
    }
}
