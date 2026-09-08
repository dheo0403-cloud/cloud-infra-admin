import React, { useEffect, useState } from 'react';
import { getVertexAiMetrics, VertexAiMetricsDto } from '../services/api';

const VertexAiOperationsPanel: React.FC = () => {
    const [metrics, setMetrics] = useState<VertexAiMetricsDto | null>(null);
    const [loading, setLoading] = useState<boolean>(true);
    const [isRefreshing, setIsRefreshing] = useState<boolean>(false);

    const fetchMetrics = async () => {
        setIsRefreshing(true);
        try {
            const res = await getVertexAiMetrics();
            if (res.data) {
                setMetrics(res.data);
            }
        } catch (err) {
            console.error('Failed to fetch Vertex AI operations metrics:', err);
        } finally {
            setLoading(false);
            setIsRefreshing(false);
        }
    };

    useEffect(() => {
        fetchMetrics();
    }, []);

    // 기본 폴백 데이터 (로딩 시에도 깨짐 없이 UI 렌더링)
    const data: VertexAiMetricsDto = metrics || {
        dates: ['09-02', '09-03', '09-04', '09-05', '09-06', '09-07', '09-08'],
        inputTokensTrend: [1420000, 1680000, 1950000, 1540000, 2100000, 2480000, 2820000],
        outputTokensTrend: [420000, 510000, 630000, 480000, 690000, 820000, 940000],
        rpmQuotaUsagePercent: 68.4,
        tpdQuotaUsagePercent: 83.6,
        currentRpm: 684,
        maxRpmQuota: 1000,
        currentTpd: 3760000,
        maxTpdQuota: 4500000,
        quotaAlert: true,
        totalEndpoints: 4,
        activeEndpoints: 3,
        idleEndpoints: 1,
        allocatedGpus: 2,
        allocatedTpus: 0,
        gpuModel: 'NVIDIA L4 × 2 (us-central1)',
        estimatedHourlyCost: 1.42,
        estimatedMonthlyCost: 1022.4,
        geminiFlashRatio: 68.0,
        geminiProRatio: 24.0,
        fineTunedRatio: 8.0,
        promptCacheHitRatio: 32.5,
        rateLimit429Errors: 3,
        safetyFilterBlocks: 12,
        avgLatencyMs: 420,
        lastUpdated: new Date().toLocaleTimeString('ko-KR')
    };

    // 토큰 합산 계산 (M 단위)
    const totalInputTokens = data.inputTokensTrend.reduce((a, b) => a + b, 0);
    const totalOutputTokens = data.outputTokensTrend.reduce((a, b) => a + b, 0);
    const total7DaysTokensM = ((totalInputTokens + totalOutputTokens) / 1000000).toFixed(1);

    // 차트 최대값 계산
    const maxTokenValue = Math.max(...data.inputTokensTrend, ...data.outputTokensTrend, 3000000);

    return (
        <div className="card mb-4" style={{ border: '1px solid rgba(225, 78, 202, 0.25)', boxShadow: '0 8px 24px rgba(0,0,0,0.35)' }}>
            {/* Header */}
            <div className="card-header border-0 d-flex flex-wrap justify-content-between align-items-center" style={{ background: 'linear-gradient(90deg, rgba(225,78,202,0.1) 0%, rgba(29,140,248,0.1) 100%)', borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                <div>
                    <h5 className="card-category text-primary" style={{ letterSpacing: '1px', textTransform: 'uppercase', fontSize: '11px', fontWeight: 700 }}>
                        <i className="fas fa-brain mr-1"></i> AI Operations & Cost Intelligence
                    </h5>
                    <h3 className="card-title text-white font-weight-300 m-0" style={{ fontFamily: 'Poppins, sans-serif' }}>
                        <i className="fas fa-robot text-primary mr-2"></i>
                        GCP Vertex AI & GenAI Operations Center (생성형 AI 자원 & 비용 관제)
                    </h3>
                </div>
                <div className="d-flex align-items-center gap-2 mt-2 mt-sm-0">
                    <span className="badge badge-bd-pink mr-2" style={{ fontSize: '11px', padding: '5px 10px' }}>
                        <i className="fas fa-satellite-dish mr-1 text-danger"></i> Live Telemetry
                    </span>
                    {data.quotaAlert && (
                        <span className="badge badge-bd-warning mr-2" style={{ fontSize: '11px', padding: '5px 10px', animation: 'pulse 2s infinite' }}>
                            <i className="fas fa-exclamation-triangle mr-1 text-warning"></i> Quota 80% 초과 경보
                        </span>
                    )}
                    <button
                        className="btn btn-sm btn-outline-secondary text-light"
                        onClick={fetchMetrics}
                        disabled={isRefreshing}
                        title="지표 새로고침"
                        style={{ borderRadius: '6px', padding: '4px 10px' }}
                    >
                        <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''} mr-1`}></i> 새로고침
                    </button>
                </div>
            </div>

            <div className="card-body p-4">
                <div className="row">
                    {/* Left 7 Columns: Token Usage & Quota Trend */}
                    <div className="col-lg-7 col-md-12 mb-4 mb-lg-0 border-right-lg border-secondary pr-lg-4">
                        <div className="d-flex justify-content-between align-items-center mb-3">
                            <div>
                                <h4 className="text-white font-weight-600 mb-1">
                                    <i className="fas fa-chart-bar text-info mr-2"></i>토큰 사용량 & API 할당량 트렌드 (최근 7일)
                                </h4>
                                <span className="text-muted small">Input / Output Token 일별 추이 및 선제적 Quota 관리</span>
                            </div>
                            <div className="d-flex align-items-center gap-3 small">
                                <span className="d-flex align-items-center mr-3">
                                    <span style={{ display: 'inline-block', width: '10px', height: '10px', backgroundColor: '#00f2c3', borderRadius: '2px', marginRight: '5px' }}></span>
                                    <span className="text-light">Input Tokens</span>
                                </span>
                                <span className="d-flex align-items-center">
                                    <span style={{ display: 'inline-block', width: '10px', height: '10px', backgroundColor: '#e14eca', borderRadius: '2px', marginRight: '5px' }}></span>
                                    <span className="text-light">Output Tokens</span>
                                </span>
                            </div>
                        </div>

                        {/* Top 3 Metric Chips */}
                        <div className="row mb-3">
                            <div className="col-sm-4 mb-2 mb-sm-0">
                                <div className="p-2 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.05)' }}>
                                    <span className="text-muted small d-block">7일 누적 토큰</span>
                                    <strong className="text-white" style={{ fontSize: '16px', fontFamily: 'JetBrains Mono, monospace' }}>
                                        {total7DaysTokensM}M <span style={{ fontSize: '11px', color: '#94a3b8' }}>Tokens</span>
                                    </strong>
                                </div>
                            </div>
                            <div className="col-sm-4 mb-2 mb-sm-0">
                                <div className="p-2 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.05)' }}>
                                    <span className="text-muted small d-block">RPM 소진율</span>
                                    <strong className="text-info" style={{ fontSize: '16px', fontFamily: 'JetBrains Mono, monospace' }}>
                                        {data.rpmQuotaUsagePercent}% <span style={{ fontSize: '11px', color: '#94a3b8' }}>({data.currentRpm}/{data.maxRpmQuota})</span>
                                    </strong>
                                </div>
                            </div>
                            <div className="col-sm-4">
                                <div className="p-2 rounded" style={{ background: data.tpdQuotaUsagePercent >= 80 ? 'rgba(255,56,96,0.1)' : '#1d1e2c', border: data.tpdQuotaUsagePercent >= 80 ? '1px solid #ff3860' : '1px solid rgba(255,255,255,0.05)' }}>
                                    <span className="text-muted small d-block">TPD 소진율</span>
                                    <strong className={data.tpdQuotaUsagePercent >= 80 ? 'text-danger' : 'text-success'} style={{ fontSize: '16px', fontFamily: 'JetBrains Mono, monospace' }}>
                                        {data.tpdQuotaUsagePercent}% {data.tpdQuotaUsagePercent >= 80 ? '🚨' : ''}
                                    </strong>
                                </div>
                            </div>
                        </div>

                        {/* SVG Bar Chart (7 Days Trend) */}
                        <div className="p-3 rounded mb-3" style={{ background: '#171822', border: '1px solid rgba(255,255,255,0.05)' }}>
                            <div style={{ height: '140px', display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', padding: '10px 5px 0 5px' }}>
                                {data.dates.map((date, idx) => {
                                    const inTokens = data.inputTokensTrend[idx] || 0;
                                    const outTokens = data.outputTokensTrend[idx] || 0;
                                    const inHeightPercent = Math.max(10, Math.min(100, (inTokens / maxTokenValue) * 100));
                                    const outHeightPercent = Math.max(8, Math.min(100, (outTokens / maxTokenValue) * 100));

                                    return (
                                        <div key={idx} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', margin: '0 3px' }}>
                                            <div style={{ width: '100%', display: 'flex', alignItems: 'flex-end', justifyContent: 'center', gap: '3px', height: '100px' }}>
                                                {/* Input Token Bar */}
                                                <div
                                                    title={`Input: ${(inTokens / 1000).toFixed(0)}K`}
                                                    style={{
                                                        width: '45%',
                                                        height: `${inHeightPercent}%`,
                                                        background: 'linear-gradient(180deg, #00f2c3 0%, #009879 100%)',
                                                        borderRadius: '3px 3px 0 0',
                                                        transition: 'all 0.3s ease'
                                                    }}
                                                ></div>
                                                {/* Output Token Bar */}
                                                <div
                                                    title={`Output: ${(outTokens / 1000).toFixed(0)}K`}
                                                    style={{
                                                        width: '45%',
                                                        height: `${outHeightPercent}%`,
                                                        background: 'linear-gradient(180deg, #e14eca 0%, #ba2bb0 100%)',
                                                        borderRadius: '3px 3px 0 0',
                                                        transition: 'all 0.3s ease'
                                                    }}
                                                ></div>
                                            </div>
                                            <span style={{ fontSize: '10px', color: '#94a3b8', marginTop: '6px', fontFamily: 'JetBrains Mono, monospace' }}>
                                                {date}
                                            </span>
                                        </div>
                                    );
                                })}
                            </div>
                        </div>

                        {/* API Quota Progress Bars */}
                        <div className="mt-3">
                            {/* RPM Bar */}
                            <div className="mb-2">
                                <div className="d-flex justify-content-between small font-weight-600 mb-1">
                                    <span className="text-light"><i className="fas fa-tachometer-alt text-info mr-1"></i>RPM (분당 요청수) Quota</span>
                                    <span className="text-info font-weight-bold">{data.rpmQuotaUsagePercent}% ({data.currentRpm} / {data.maxRpmQuota} RPM)</span>
                                </div>
                                <div className="progress" style={{ height: '10px', backgroundColor: '#1d1e2c', borderRadius: '4px' }}>
                                    <div
                                        className="progress-bar"
                                        role="progressbar"
                                        style={{ width: `${data.rpmQuotaUsagePercent}%`, background: 'linear-gradient(90deg, #1d8cf8, #00f2c3)' }}
                                    ></div>
                                </div>
                            </div>

                            {/* TPD Bar */}
                            <div>
                                <div className="d-flex justify-content-between small font-weight-600 mb-1">
                                    <span className="text-light"><i className="fas fa-coins text-warning mr-1"></i>TPD (일일 토큰 한도) Quota</span>
                                    <span className={data.tpdQuotaUsagePercent >= 80 ? 'text-danger font-weight-bold' : 'text-success font-weight-bold'}>
                                        {data.tpdQuotaUsagePercent}% ({(data.currentTpd / 1000000).toFixed(2)}M / {(data.maxTpdQuota / 1000000).toFixed(2)}M)
                                    </span>
                                </div>
                                <div className="progress" style={{ height: '10px', backgroundColor: '#1d1e2c', borderRadius: '4px' }}>
                                    <div
                                        className="progress-bar"
                                        role="progressbar"
                                        style={{
                                            width: `${data.tpdQuotaUsagePercent}%`,
                                            background: data.tpdQuotaUsagePercent >= 80
                                                ? 'linear-gradient(90deg, #ff8d72, #fd5d93)'
                                                : 'linear-gradient(90deg, #00f2c3, #1d8cf8)'
                                        }}
                                    ></div>
                                </div>
                                {data.tpdQuotaUsagePercent >= 80 && (
                                    <div className="mt-1 d-flex align-items-center">
                                        <small className="text-danger font-weight-bold" style={{ fontSize: '10px' }}>
                                            <i className="fas fa-exclamation-circle mr-1"></i>80% 임계치 초과: 트래픽 급증 대비 GCP Console에서 Quota 사전 증설을 권장합니다.
                                        </small>
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>

                    {/* Right 5 Columns: 3 Smart Widget Boxes */}
                    <div className="col-lg-5 col-md-12 d-flex flex-column justify-content-between">
                        {/* Widget 1: Endpoints & GPU Status (Cost Leak Guard) */}
                        <div className="p-3 mb-3 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.06)' }}>
                            <div className="d-flex justify-content-between align-items-center mb-2">
                                <h5 className="text-white font-weight-600 m-0" style={{ fontSize: '13px' }}>
                                    <i className="fas fa-server text-warning mr-2"></i>비용 발생 엔드포인트 & GPU 상태
                                </h5>
                                {data.idleEndpoints > 0 ? (
                                    <span className="badge badge-bd-warning" style={{ fontSize: '10px' }}>
                                        <i className="fas fa-exclamation-triangle mr-1 text-warning"></i>유휴 1개 감지
                                    </span>
                                ) : (
                                    <span className="badge badge-bd-teal" style={{ fontSize: '10px' }}>정상 가동</span>
                                )}
                            </div>
                            <div className="d-flex justify-content-between align-items-center small text-muted mb-1">
                                <span>활성 엔드포인트: <strong className="text-white">{data.activeEndpoints}개</strong> <span className="text-muted">(총 {data.totalEndpoints}개)</span></span>
                                <span>가속기: <strong className="text-info">{data.gpuModel}</strong></span>
                            </div>
                            <div className="d-flex justify-content-between align-items-center mt-2 pt-2 border-top border-secondary small">
                                <span className="text-muted">예상 인프라 비용:</span>
                                <span className="text-white font-weight-bold" style={{ fontFamily: 'JetBrains Mono, monospace' }}>
                                    ${data.estimatedHourlyCost}/hr <span style={{ color: '#94a3b8', fontSize: '11px' }}>(${data.estimatedMonthlyCost}/mo)</span>
                                </span>
                            </div>
                        </div>

                        {/* Widget 2: Model Distribution & Cache Savings */}
                        <div className="p-3 mb-3 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.06)' }}>
                            <div className="d-flex justify-content-between align-items-center mb-2">
                                <h5 className="text-white font-weight-600 m-0" style={{ fontSize: '13px' }}>
                                    <i className="fas fa-layer-group text-info mr-2"></i>주력 AI 모델 호출 비중 (비용 최적화)
                                </h5>
                                <span className="badge badge-bd-blue" style={{ fontSize: '10px' }}>
                                    캐싱 {data.promptCacheHitRatio}%
                                </span>
                            </div>
                            {/* Segmented Bar */}
                            <div className="progress mb-2" style={{ height: '8px', backgroundColor: '#14151f', borderRadius: '3px' }}>
                                <div className="progress-bar" style={{ width: `${data.geminiFlashRatio}%`, backgroundColor: '#00f2c3' }} title={`Flash: ${data.geminiFlashRatio}%`}></div>
                                <div className="progress-bar" style={{ width: `${data.geminiProRatio}%`, backgroundColor: '#1d8cf8' }} title={`Pro: ${data.geminiProRatio}%`}></div>
                                <div className="progress-bar" style={{ width: `${data.fineTunedRatio}%`, backgroundColor: '#e14eca' }} title={`Custom: ${data.fineTunedRatio}%`}></div>
                            </div>
                            <div className="d-flex justify-content-between small text-muted" style={{ fontSize: '10px' }}>
                                <span><span style={{ color: '#00f2c3' }}>●</span> Flash (저비용): <strong className="text-white">{data.geminiFlashRatio}%</strong></span>
                                <span><span style={{ color: '#1d8cf8' }}>●</span> Pro (고성능): <strong className="text-white">{data.geminiProRatio}%</strong></span>
                                <span><span style={{ color: '#e14eca' }}>●</span> Custom: <strong className="text-white">{data.fineTunedRatio}%</strong></span>
                            </div>
                        </div>

                        {/* Widget 3: Reliability & Safety Guard */}
                        <div className="p-3 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.06)' }}>
                            <div className="d-flex justify-content-between align-items-center mb-2">
                                <h5 className="text-white font-weight-600 m-0" style={{ fontSize: '13px' }}>
                                    <i className="fas fa-shield-alt text-success mr-2"></i>속도 제한(429) & 안전 필터 관제
                                </h5>
                                <span className="badge badge-bd-teal" style={{ fontSize: '10px' }}>
                                    지연 {data.avgLatencyMs}ms ⚡
                                </span>
                            </div>
                            <div className="row text-center">
                                <div className="col-6 border-right border-secondary">
                                    <span className="text-muted d-block small" style={{ fontSize: '10px' }}>429 Rate Limit</span>
                                    <strong className={data.rateLimit429Errors > 5 ? 'text-danger' : 'text-warning'} style={{ fontSize: '14px', fontFamily: 'JetBrains Mono, monospace' }}>
                                        {data.rateLimit429Errors}건
                                    </strong>
                                </div>
                                <div className="col-6">
                                    <span className="text-muted d-block small" style={{ fontSize: '10px' }}>Safety 차단 프롬프트</span>
                                    <strong className="text-info" style={{ fontSize: '14px', fontFamily: 'JetBrains Mono, monospace' }}>
                                        {data.safetyFilterBlocks}건 🛡️
                                    </strong>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default VertexAiOperationsPanel;
