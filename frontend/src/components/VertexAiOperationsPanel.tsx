import React, { useEffect, useState } from 'react';
import { getVertexAiMetrics, VertexAiMetricsDto } from '../services/api';

interface VertexAiOperationsPanelProps {
    projectId?: string;
    targetYearMonth?: string;
}

const VertexAiOperationsPanel: React.FC<VertexAiOperationsPanelProps> = ({ projectId, targetYearMonth }) => {
    const [metrics, setMetrics] = useState<VertexAiMetricsDto | null>(null);
    const [loading, setLoading] = useState<boolean>(true);
    const [isRefreshing, setIsRefreshing] = useState<boolean>(false);

    const fetchMetrics = async () => {
        setIsRefreshing(true);
        try {
            const res = await getVertexAiMetrics(projectId, targetYearMonth);
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
    }, [projectId, targetYearMonth]);

    const hasData = Boolean(metrics && metrics.dates && metrics.dates.length > 0);

    // 기본 데이터 (데이터가 없을 때는 0으로 초기화)
    const data: VertexAiMetricsDto = metrics || {
        projectId: projectId || 'hcompany-485701',
        customerName: '고객사 GCP 프로젝트',
        dates: [],
        inputTokensTrend: [],
        outputTokensTrend: [],
        rpmQuotaUsagePercent: 0,
        tpdQuotaUsagePercent: 0,
        currentRpm: 0,
        maxRpmQuota: 1000,
        currentTpd: 0,
        maxTpdQuota: 4500000,
        quotaAlert: false,
        totalEndpoints: 0,
        activeEndpoints: 0,
        idleEndpoints: 0,
        allocatedGpus: 0,
        allocatedTpus: 0,
        gpuModel: 'N/A',
        estimatedHourlyCost: 0,
        estimatedMonthlyCost: 0,
        geminiFlashRatio: 0,
        geminiProRatio: 0,
        fineTunedRatio: 0,
        promptCacheHitRatio: 0,
        rateLimit429Errors: 0,
        safetyFilterBlocks: 0,
        avgLatencyMs: 0,
        lastUpdated: new Date().toLocaleTimeString('ko-KR')
    };

    // 토큰 합산 계산 (M 단위)
    const totalInputTokens = (data.inputTokensTrend || []).reduce((a, b) => a + b, 0);
    const totalOutputTokens = (data.outputTokensTrend || []).reduce((a, b) => a + b, 0);
    const total7DaysTokensM = ((totalInputTokens + totalOutputTokens) / 1000000).toFixed(1);

    // 차트 최대값 계산
    const maxTokenValue = Math.max(...(data.inputTokensTrend || [0]), ...(data.outputTokensTrend || [0]), 1000000);

    // 데이터 부재 여부 판별 (토큰 트렌드가 모두 0이고, 엔드포인트/RPM이 0인 경우)
    const isAiEmpty = !hasData || ((totalInputTokens + totalOutputTokens === 0) &&
        (data.totalEndpoints === 0) &&
        (data.currentRpm === 0));

    return (
        <div className={isAiEmpty ? "print-hide-empty" : ""} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
            {/* Left Card: Vertex AI Token Usage & Quota Trend (2-Column Standard Report Card) */}
            <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                <div className="report-card-title">
                    <span style={{ display: 'flex', alignItems: 'center' }}>
                        <i className="fas fa-brain mr-2" style={{ color: '#2563eb' }}></i>
                        Vertex AI 토큰 및 Quota 트렌드
                    </span>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <span style={{ fontSize: '10px', color: '#64748b', backgroundColor: '#f1f5f9', padding: '2px 8px', borderRadius: '4px', border: '1px solid #e2e8f0', fontWeight: 600 }}>
                            {data.projectId || 'hcompany-485701'}
                        </span>
                        <button
                            onClick={fetchMetrics}
                            disabled={isRefreshing}
                            title="지표 새로고침"
                            style={{
                                border: '1px solid #cbd5e1',
                                backgroundColor: '#ffffff',
                                color: '#64748b',
                                borderRadius: '4px',
                                padding: '2px 8px',
                                fontSize: '11px',
                                cursor: 'pointer',
                                display: 'flex',
                                alignItems: 'center',
                                gap: '4px',
                                transition: 'all 0.2s ease'
                            }}
                        >
                            <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
                            새로고침
                        </button>
                    </div>
                </div>

                <div style={{ flexGrow: 1, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                    {hasData ? (
                        <>
                            {/* Top 3 Metric Summary Chips */}
                            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '8px', marginBottom: '10px' }}>
                                <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '8px 10px' }}>
                                    <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>7일 누적 토큰</span>
                                    <strong style={{ fontSize: '13px', fontWeight: 800, color: '#0f172a', fontFamily: 'Pretendard, sans-serif' }}>
                                        {total7DaysTokensM}M <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Tokens</span>
                                    </strong>
                                </div>
                                <div style={{ backgroundColor: '#eff6ff', border: '1px solid #dbeafe', borderRadius: '8px', padding: '8px 10px' }}>
                                    <span style={{ display: 'block', fontSize: '10px', color: '#2563eb', fontWeight: 600 }}>RPM 소진율</span>
                                    <strong style={{ fontSize: '13px', fontWeight: 800, color: '#1d4ed8', fontFamily: 'Pretendard, sans-serif' }}>
                                        {data.rpmQuotaUsagePercent}% <span style={{ fontSize: '9px', fontWeight: 500, color: '#60a5fa' }}>({data.currentRpm}/{data.maxRpmQuota})</span>
                                    </strong>
                                </div>
                                <div style={{
                                    backgroundColor: data.tpdQuotaUsagePercent >= 80 ? '#fef2f2' : '#ecfdf5',
                                    border: data.tpdQuotaUsagePercent >= 80 ? '1px solid #fecaca' : '1px solid #a7f3d0',
                                    borderRadius: '8px',
                                    padding: '8px 10px'
                                }}>
                                    <span style={{ display: 'block', fontSize: '10px', color: data.tpdQuotaUsagePercent >= 80 ? '#dc2626' : '#059669', fontWeight: 600 }}>
                                        TPD 소진율 {data.tpdQuotaUsagePercent >= 80 ? '🚨' : ''}
                                    </span>
                                    <strong style={{ fontSize: '13px', fontWeight: 800, color: data.tpdQuotaUsagePercent >= 80 ? '#b91c1c' : '#047857', fontFamily: 'Pretendard, sans-serif' }}>
                                        {data.tpdQuotaUsagePercent}%
                                    </strong>
                                </div>
                            </div>

                            {/* 7-Day Trend Chart (Light Theme) */}
                            <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px', marginBottom: '10px' }}>
                                <div style={{ height: '90px', display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', borderBottom: '1px dashed #cbd5e1', paddingBottom: '4px' }}>
                                    {data.dates.map((date, idx) => {
                                        const inTokens = data.inputTokensTrend[idx] || 0;
                                        const outTokens = data.outputTokensTrend[idx] || 0;
                                        const inHeightPercent = Math.max(14, Math.min(100, (inTokens / maxTokenValue) * 100));
                                        const outHeightPercent = Math.max(10, Math.min(100, (outTokens / maxTokenValue) * 100));

                                        return (
                                            <div key={idx} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                <div style={{ width: '100%', display: 'flex', alignItems: 'flex-end', justifyContent: 'center', gap: '3px', height: '100%' }}>
                                                    <div
                                                        title={`Input: ${(inTokens / 1000).toFixed(0)}K`}
                                                        style={{
                                                            width: '9px',
                                                            height: `${inHeightPercent}%`,
                                                            backgroundColor: '#3b82f6',
                                                            borderRadius: '3px 3px 0 0'
                                                        }}
                                                    ></div>
                                                    <div
                                                        title={`Output: ${(outTokens / 1000).toFixed(0)}K`}
                                                        style={{
                                                            width: '9px',
                                                            height: `${outHeightPercent}%`,
                                                            backgroundColor: '#10b981',
                                                            borderRadius: '3px 3px 0 0'
                                                        }}
                                                    ></div>
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>
                                {/* Dates row */}
                                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '9px', color: '#64748b', marginTop: '4px' }}>
                                    {data.dates.map((d, idx) => (
                                        <span key={idx} style={{ flex: 1, textAlign: 'center' }}>{d}</span>
                                    ))}
                                </div>
                                {/* Legend */}
                                <div style={{ display: 'flex', justifyContent: 'center', gap: '14px', fontSize: '10px', marginTop: '6px' }}>
                                    <span style={{ color: '#334155', fontWeight: 600, display: 'flex', alignItems: 'center' }}>
                                        <span style={{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '2px', backgroundColor: '#3b82f6', marginRight: '4px' }}></span>Input Tokens
                                    </span>
                                    <span style={{ color: '#334155', fontWeight: 600, display: 'flex', alignItems: 'center' }}>
                                        <span style={{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '2px', backgroundColor: '#10b981', marginRight: '4px' }}></span>Output Tokens
                                    </span>
                                </div>
                            </div>

                            {/* Quota Progress Bars */}
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                                <div>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', fontWeight: 700, marginBottom: '2px' }}>
                                        <span style={{ color: '#475569' }}><i className="fas fa-tachometer-alt mr-1" style={{ color: '#2563eb' }}></i>RPM Quota</span>
                                        <span style={{ color: '#2563eb' }}>{data.rpmQuotaUsagePercent}% ({data.currentRpm} / {data.maxRpmQuota} RPM)</span>
                                    </div>
                                    <div style={{ width: '100%', height: '6px', backgroundColor: '#e2e8f0', borderRadius: '4px', overflow: 'hidden' }}>
                                        <div style={{ width: `${data.rpmQuotaUsagePercent}%`, height: '100%', backgroundColor: '#3b82f6', borderRadius: '4px' }}></div>
                                    </div>
                                </div>
                                <div>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', fontWeight: 700, marginBottom: '2px' }}>
                                        <span style={{ color: '#475569' }}><i className="fas fa-coins mr-1" style={{ color: '#d97706' }}></i>TPD Quota</span>
                                        <span style={{ color: data.tpdQuotaUsagePercent >= 80 ? '#dc2626' : '#059669' }}>
                                            {data.tpdQuotaUsagePercent}% ({(data.currentTpd / 1000000).toFixed(2)}M / {(data.maxTpdQuota / 1000000).toFixed(2)}M)
                                        </span>
                                    </div>
                                    <div style={{ width: '100%', height: '6px', backgroundColor: '#e2e8f0', borderRadius: '4px', overflow: 'hidden' }}>
                                        <div style={{
                                            width: `${data.tpdQuotaUsagePercent}%`,
                                            height: '100%',
                                            backgroundColor: data.tpdQuotaUsagePercent >= 80 ? '#ef4444' : '#10b981',
                                            borderRadius: '4px'
                                        }}></div>
                                    </div>
                                </div>
                            </div>
                        </>
                    ) : (
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '180px', backgroundColor: '#f8fafc', borderRadius: '8px', border: '1px dashed #cbd5e1', padding: '20px' }}>
                            <i className="fas fa-info-circle" style={{ fontSize: '24px', color: '#94a3b8', marginBottom: '8px' }}></i>
                            <span style={{ fontSize: '12px', fontWeight: 700, color: '#475569' }}>수집된 Vertex AI 지표가 없습니다.</span>
                            <span style={{ fontSize: '10px', color: '#94a3b8', marginTop: '4px' }}>선택된 연월({targetYearMonth || '당월'})의 API 호출 및 토큰 사용량 기록이 존재하지 않습니다.</span>
                        </div>
                    )}
                </div>
            </div>

            {/* Right Card: Vertex AI Core Metrics & Cost Intelligence (3 Horizontal Cards) */}
            <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                <div className="report-card-title">
                    <span style={{ display: 'flex', alignItems: 'center' }}>
                        <i className="fas fa-robot mr-2" style={{ color: '#2563eb' }}></i>
                        Vertex AI 핵심 운영 지표
                    </span>
                    <div>
                        {hasData ? (
                            data.quotaAlert ? (
                                <span style={{ fontSize: '10px', fontWeight: 700, color: '#dc2626', backgroundColor: '#fef2f2', padding: '2px 8px', borderRadius: '4px', border: '1px solid #fecaca' }}>
                                    <i className="fas fa-exclamation-triangle mr-1"></i>Quota 경보
                                </span>
                            ) : (
                                <span style={{ fontSize: '10px', fontWeight: 700, color: '#059669', backgroundColor: '#ecfdf5', padding: '2px 8px', borderRadius: '4px', border: '1px solid #a7f3d0' }}>
                                    <i className="fas fa-check-circle mr-1"></i>정상 가동
                                </span>
                            )
                        ) : (
                            <span style={{ fontSize: '10px', fontWeight: 600, color: '#64748b', backgroundColor: '#f1f5f9', padding: '2px 8px', borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                                미사용
                            </span>
                        )}
                    </div>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', margin: 'auto 0' }}>
                    {/* Item Card 1: Endpoints & GPU Status */}
                    <div style={{
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px',
                        backgroundColor: hasData && data.totalEndpoints > 0 ? '#fffbeb' : '#f8fafc',
                        border: hasData && data.totalEndpoints > 0 ? '1px solid #fde68a' : '1px solid #e2e8f0',
                        borderRadius: '8px', position: 'relative'
                    }}>
                        <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: hasData && data.totalEndpoints > 0 ? '#f59e0b' : '#94a3b8', borderRadius: '8px 0 0 8px' }}></div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                            <div style={{
                                width: '30px', height: '30px', borderRadius: '50%',
                                backgroundColor: hasData && data.totalEndpoints > 0 ? '#fef3c7' : '#f1f5f9',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                color: hasData && data.totalEndpoints > 0 ? '#d97706' : '#64748b', fontSize: '13px'
                            }}>
                                <i className="fas fa-server"></i>
                            </div>
                            <div>
                                <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>엔드포인트 & GPU 인프라</span>
                                <span style={{ display: 'block', fontSize: '9px', color: hasData && data.totalEndpoints > 0 ? '#92400e' : '#64748b', marginTop: '1px' }}>
                                    {hasData ? `활성 ${data.activeEndpoints}개 (총 ${data.totalEndpoints}개) · ${data.gpuModel}` : '미사용 (배포된 엔드포인트 없음)'}
                                </span>
                            </div>
                        </div>
                        <div style={{ textAlign: 'right' }}>
                            {hasData ? (
                                <>
                                    <span style={{ fontSize: '12px', fontWeight: 800, color: '#b45309', display: 'block' }}>
                                        ${data.estimatedHourlyCost}/hr
                                    </span>
                                    <span style={{ fontSize: '9px', color: '#78350f', display: 'block' }}>
                                        (${data.estimatedMonthlyCost}/mo)
                                    </span>
                                </>
                            ) : (
                                <span style={{ fontSize: '11px', fontWeight: 700, color: '#64748b', backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', border: '1px solid #cbd5e1' }}>
                                    N/A
                                </span>
                            )}
                        </div>
                    </div>

                    {/* Item Card 2: Model Distribution & Cache Savings */}
                    <div style={{
                        display: 'flex', flexDirection: 'column', padding: '8px 12px',
                        backgroundColor: hasData ? '#eff6ff' : '#f8fafc',
                        border: hasData ? '1px solid #bfdbfe' : '1px solid #e2e8f0',
                        borderRadius: '8px', position: 'relative', gap: '4px'
                    }}>
                        <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: hasData ? '#2563eb' : '#94a3b8', borderRadius: '8px 0 0 8px' }}></div>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingLeft: '4px' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                <div style={{
                                    width: '30px', height: '30px', borderRadius: '50%',
                                    backgroundColor: hasData ? '#dbeafe' : '#f1f5f9',
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                    color: hasData ? '#1d4ed8' : '#64748b', fontSize: '13px'
                                }}>
                                    <i className="fas fa-layer-group"></i>
                                </div>
                                <div>
                                    <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>주력 AI 모델 호출 비중</span>
                                    <span style={{ display: 'block', fontSize: '9px', color: hasData ? '#1e40af' : '#64748b', marginTop: '1px' }}>
                                        {hasData ? `Flash ${data.geminiFlashRatio}% · Pro ${data.geminiProRatio}% · Custom ${data.fineTunedRatio}%` : '호출 내역 없음'}
                                    </span>
                                </div>
                            </div>
                            <div style={{ textAlign: 'right' }}>
                                <span style={{ fontSize: '11px', fontWeight: 800, color: hasData ? '#1d4ed8' : '#64748b', backgroundColor: hasData ? '#dbeafe' : '#f1f5f9', padding: '2px 6px', borderRadius: '4px', border: hasData ? 'none' : '1px solid #cbd5e1' }}>
                                    {hasData ? `캐시 ${data.promptCacheHitRatio}%` : 'N/A'}
                                </span>
                            </div>
                        </div>
                        {hasData && (
                            <div style={{ width: '100%', height: '5px', backgroundColor: '#e2e8f0', borderRadius: '3px', overflow: 'hidden', display: 'flex', marginTop: '2px' }}>
                                <div style={{ width: `${data.geminiFlashRatio}%`, backgroundColor: '#3b82f6' }} title={`Flash: ${data.geminiFlashRatio}%`}></div>
                                <div style={{ width: `${data.geminiProRatio}%`, backgroundColor: '#6366f1' }} title={`Pro: ${data.geminiProRatio}%`}></div>
                                <div style={{ width: `${data.fineTunedRatio}%`, backgroundColor: '#8b5cf6' }} title={`Custom: ${data.fineTunedRatio}%`}></div>
                            </div>
                        )}
                    </div>

                    {/* Item Card 3: Reliability & Safety Guard */}
                    <div style={{
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px',
                        backgroundColor: hasData ? '#f0fdf4' : '#f8fafc',
                        border: hasData ? '1px solid #bbf7d0' : '1px solid #e2e8f0',
                        borderRadius: '8px', position: 'relative'
                    }}>
                        <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: hasData ? '#10b981' : '#94a3b8', borderRadius: '8px 0 0 8px' }}></div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                            <div style={{
                                width: '30px', height: '30px', borderRadius: '50%',
                                backgroundColor: hasData ? '#dcfce7' : '#f1f5f9',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                color: hasData ? '#15803d' : '#64748b', fontSize: '13px'
                            }}>
                                <i className="fas fa-shield-alt"></i>
                            </div>
                            <div>
                                <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>속도 제한(429) & Safety 필터</span>
                                <span style={{ display: 'block', fontSize: '9px', color: hasData ? '#166534' : '#64748b', marginTop: '1px' }}>
                                    {hasData ? `평균 지연 ${data.avgLatencyMs}ms · Safety 차단 ${data.safetyFilterBlocks}건` : '모니터링 대상 없음'}
                                </span>
                            </div>
                        </div>
                        <div style={{ textAlign: 'right' }}>
                            <span style={{
                                fontSize: '11px',
                                fontWeight: 800,
                                color: hasData ? (data.rateLimit429Errors > 0 ? '#b91c1c' : '#15803d') : '#64748b',
                                backgroundColor: hasData ? (data.rateLimit429Errors > 0 ? '#fee2e2' : '#dcfce7') : '#f1f5f9',
                                padding: '2px 6px',
                                borderRadius: '4px',
                                border: hasData ? 'none' : '1px solid #cbd5e1'
                            }}>
                                {hasData ? (data.rateLimit429Errors > 0 ? `429 에러 ${data.rateLimit429Errors}건` : '429 에러 0건 (안정)') : 'N/A'}
                            </span>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default VertexAiOperationsPanel;
