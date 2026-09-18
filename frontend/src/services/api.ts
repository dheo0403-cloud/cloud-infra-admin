import axios from 'axios';

const API_BASE_URL = '/api';

export interface CloudProject {
    id?: string;
    projectId: string;
}

export interface InfraEnvironment {
    id?: string;
    customerId?: string;
    providerType: string; // 'GCP' | 'AZURE'
    environmentName: string;
    projects: CloudProject[];
    encryptedSecret?: string;
    azureTenantId?: string;
    azureClientId?: string;
    createdAt?: string;
}

export interface InfraCustomer {
    id?: string;
    name: string;
    contactPerson: string;
    contactEmail: string;
    reportFrequency?: string; // 'MONTHLY' | 'QUARTERLY'
    mspGrade?: string;
    mspSalesRep?: string;
    mspRep?: string;
    jiraProjectKeyGcp?: string;
    jiraProjectKeyAzure?: string;
    environments?: InfraEnvironment[];
    createdAt?: string;
    updatedAt?: string;
}

// Customers
export const getCustomers = () => axios.get<InfraCustomer[]>(`${API_BASE_URL}/customers`);
export const getCustomer = (id: string) => axios.get<InfraCustomer>(`${API_BASE_URL}/customers/${id}`);
export const saveCustomer = (customer: InfraCustomer) => axios.post<InfraCustomer>(`${API_BASE_URL}/customers`, customer);
export const deleteCustomer = (id: string) => axios.delete(`${API_BASE_URL}/customers/${id}`);

// Environments
export const getEnvironmentsByCustomer = (customerId: string) => axios.get<InfraEnvironment[]>(`${API_BASE_URL}/environments/customer/${customerId}`);
export const deleteEnvironment = (id: string) => axios.delete(`${API_BASE_URL}/environments/${id}`);

// Audit Reports
export const runAudit = (envId: string) => axios.post(`${API_BASE_URL}/audit/run/${envId}`);
export const getAuditStatus = (reportId: string) => axios.get<string>(`${API_BASE_URL}/audit/status/${reportId}`);
export const downloadAuditReport = (reportId: string) => axios.get(`${API_BASE_URL}/audit/reports/download/${reportId}`, { responseType: 'blob' });
export const downloadPptxAuditReport = (reportId: string) => axios.get(`${API_BASE_URL}/audit/reports/download/pptx/${reportId}`, { responseType: 'blob' });
export const downloadPptxAuditReportDirectly = (environmentId: string, targetDate?: string) => {
    const url = (targetDate && targetDate.trim() !== '') 
        ? `${API_BASE_URL}/audit/reports/download-pptx-direct/${environmentId}?targetDate=${targetDate}` 
        : `${API_BASE_URL}/audit/reports/download-pptx-direct/${environmentId}`;
    return axios.get(url, { responseType: 'blob' });
};

// Reservations (RI / CUD)
export interface ReservationDto {
    provider: string;
    reservationName: string;
    status: string;
    startDate: string;
    expiryDate: string;
    plan: string;
    type: string;
    region: string;
    scope: string;
    resourceDetail: string;
    projectId: string;
    customerName: string;
    snapshotDate: string;
}
export const getReservations = (envId: string) => axios.get<ReservationDto[]>(`${API_BASE_URL}/reservations/${envId}`);
export const refreshReservations = (envId: string) => axios.post<ReservationDto[]>(`${API_BASE_URL}/reservations/${envId}/refresh`);
export const getUpcomingExpiryReservations = () => axios.get<ReservationDto[]>(`${API_BASE_URL}/reservations/upcoming-expiry`);

// Jira
export interface JiraIssueItem {
    issue_id: string;
    issue_key: string;
    project_key: string;
    summary: string;
    browse_url: string;
    issue_type: string;
    status_name: string;
    status_category: string;
    priority: string;
    assignee_name: string;
    assignee_email: string;
    reporter_name: string;
    reporter_email: string;
    created_at: string;
    updated_at: string;
    resolution_date: string;
    labels: string;
}

export const getJiraIssues = (projectKey: string, days: number = 7) =>
    axios.get<{ success: boolean; projectKey: string; days: number; count: number; issues: JiraIssueItem[] }>(
        `${API_BASE_URL}/jira/issues`, { params: { projectKey, days } }
    );

// 1. Direct AI Usage (AI 서비스 직접 사용) Metrics
export interface DirectAiMetricsDto {
    projectId?: string;
    customerName?: string;
    dates: string[];
    inputTokensTrend: number[];
    outputTokensTrend: number[];
    pretrainedApiCallsTrend: number[];
    totalInputTokens: number;
    totalOutputTokens: number;
    totalTokens: number;
    totalPretrainedApiCalls: number;
    visionApiCalls: number;
    speechApiCalls: number;
    translationApiCalls: number;
    nlpApiCalls: number;
    trainingNodeHours: number;
    pipelineRunsCount: number;
    workbenchUptimeHours: number;
    activeWorkbenchCount: number;
    currentRpm: number;
    maxRpmQuota: number;
    rpmQuotaUsagePercent: number;
    currentTpd: number;
    maxTpdQuota: number;
    tpdQuotaUsagePercent: number;
    quotaAlert: boolean;
    geminiFlashRatio: number;
    geminiProRatio: number;
    claudeRatio: number;
    customModelRatio: number;
    estimatedApiCost: number;
    estimatedTrainingCost: number;
    totalEstimatedDailyCost: number;
    totalEstimatedMonthlyCost: number;
    lastUpdated: string;
}

export const getDirectAiMetrics = (projectId?: string, targetYearMonth?: string) =>
    axios.get<DirectAiMetricsDto>(`${API_BASE_URL}/metrics/gcp/direct-ai`, { params: { projectId, targetYearMonth } });

// 2. Endpoint Serving (AI 엔드포인트 서빙) Metrics
export interface EndpointDetailDto {
    endpointId: string;
    endpointName: string;
    deployedModelId: string;
    deployedModelName: string;
    machineType: string;
    acceleratorType: string;
    acceleratorCount: number;
    minReplicas: number;
    maxReplicas: number;
    currentReplicas: number;
    totalRequests: number;
    qps: number;
    avgLatencyMs: number;
    p95LatencyMs: number;
    p99LatencyMs: number;
    errorCount4xx: number;
    errorCount5xx: number;
    errorRate4xx: number;
    errorRate5xx: number;
    gpuUtilizationPercent: number;
    cpuUtilizationPercent: number;
    nodeUptimeHours: number;
    endpointNodeHours: number;
    hourlyCost: number;
    monthlyCost: number;
    status: string;
}

export interface EndpointServingMetricsDto {
    projectId?: string;
    customerName?: string;
    totalRequests7d: number;
    currentQps: number;
    avgLatencyMs: number;
    p95LatencyMs: number;
    p99LatencyMs: number;
    errorRate4xxPercent: number;
    errorRate5xxPercent: number;
    successRatePercent: number;
    totalEndpoints: number;
    activeEndpoints: number;
    totalAllocatedGpus: number;
    totalEstimatedHourlyCost: number;
    totalEstimatedMonthlyCost: number;
    dates: string[];
    dailyRequestsTrend: number[];
    dailyLatencyTrend: number[];
    dailyQpsTrend: number[];
    endpoints: EndpointDetailDto[];
}

export const getEndpointServingMetrics = (projectId?: string, targetYearMonth?: string) =>
    axios.get<EndpointServingMetricsDto>(`${API_BASE_URL}/metrics/gcp/endpoint-serving`, { params: { projectId, targetYearMonth } });

// Legacy Aliases for Backward Compatibility
export type VertexAiMetricsDto = DirectAiMetricsDto;
export const getVertexAiMetrics = getDirectAiMetrics;
export type VertexEndpointMetricsDto = EndpointServingMetricsDto;
export const getVertexEndpointMetrics = getEndpointServingMetrics;



