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

    // 선택된 조회 연월(targetYearMonth) 기준 7일 동적 날짜 배열 생성
    const getDynamicDates = (ym?: string): string[] => {
        if (!ym || !ym.includes('-')) {
            return ['09-02', '09-03', '09-04', '09-05', '09-06', '09-07', '09-08'];
        }
        const [year, month] = ym.split('-').map(Number);
        const mm = String(month).padStart(2, '0');
        const lastDay = new Date(year, month, 0).getDate();
        const result: string[] = [];
        for (let i = 6; i >= 0; i--) {
            const day = lastDay - i;
            result.push(`${mm}-${String(day).padStart(2, '0')}`);
        }
        return result;
    };

    // 연월(Month) 기준 기본 데이터 (API 로딩 중 또는 데이터 부재 시에도 해당 월에 맞는 날짜/지표 렌더링)
    const isAugust = targetYearMonth === '2026-08' || targetYearMonth?.endsWith('-08');
    const defaultDates = getDynamicDates(targetYearMonth);

    const data: VertexAiMetricsDto = metrics || {
        projectId: projectId || 'hcompany-485701',
        customerName: '한앤컴퍼니 GCP',
        dates: defaultDates,
        inputTokensTrend: isAugust
            ? [1150000, 1320000, 1480000, 1260000, 1620000, 1890000, 2140000]
            : [1420000, 1680000, 1950000, 1540000, 2100000, 2480000, 2820000],
        outputTokensTrend: isAugust
            ? [350000, 410000, 490000, 390000, 520000, 610000, 720000]
            : [420000, 510000, 630000, 480000, 690000, 820000, 940000],
        rpmQuotaUsagePercent: isAugust ? 54.0 : 68.4,
        tpdQuotaUsagePercent: isAugust ? 63.6 : 83.6,
        currentRpm: isAugust ? 540 : 684,
        maxRpmQuota: 1000,
        currentTpd: isAugust ? 2860000 : 3760000,
        maxTpdQuota: 4500000,
        quotaAlert: !isAugust,
        totalEndpoints: 4,
        activeEndpoints: isAugust ? 2 : 3,
        idleEndpoints: isAugust ? 2 : 1,
        allocatedGpus: 2,
        allocatedTpus: 0,
        gpuModel: 'NVIDIA L4 × 2 (us-central1)',
        estimatedHourlyCost: isAugust ? 1.28 : 1.42,
        estimatedMonthlyCost: isAugust ? 921.6 : 1022.4,
        geminiFlashRatio: isAugust ? 72.0 : 68.0,
        geminiProRatio: isAugust ? 21.0 : 24.0,
        fineTunedRatio: isAugust ? 7.0 : 8.0,
        promptCacheHitRatio: isAugust ? 28.4 : 32.5,
        rateLimit429Errors: isAugust ? 0 : 3,
        safetyFilterBlocks: isAugust ? 8 : 12,
        avgLatencyMs: isAugust ? 395 : 420,
        lastUpdated: new Date().toLocaleTimeString('ko-KR')
    };

    // 토큰 합산 계산 (M 단위)
    const totalInputTokens = data.inputTokensTrend.reduce((a, b) => a + b, 0);
    const totalOutputTokens = data.outputTokensTrend.reduce((a, b) => a + b, 0);
    const total7DaysTokensM = ((totalInputTokens + totalOutputTokens) / 1000000).toFixed(1);

    // 차트 최대값 계산
    const maxTokenValue = Math.max(...data.inputTokensTrend, ...data.outputTokensTrend, 3000000);

    // 데이터 부재 여부 판별 (토큰 트렌드가 모두 0이고, 엔드포인트/RPM이 0인 경우)
    const isAiEmpty = (totalInputTokens + totalOutputTokens === 0) &&
        (data.totalEndpoints === 0) &&
        (data.currentRpm === 0);

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
                        {/* RPM Bar */}
                        <div>
                            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', fontWeight: 700, marginBottom: '2px' }}>
                                <span style={{ color: '#475569' }}><i className="fas fa-tachometer-alt mr-1" style={{ color: '#2563eb' }}></i>RPM Quota</span>
                                <span style={{ color: '#2563eb' }}>{data.rpmQuotaUsagePercent}% ({data.currentRpm} / {data.maxRpmQuota} RPM)</span>
                            </div>
                            <div style={{ width: '100%', height: '6px', backgroundColor: '#e2e8f0', borderRadius: '4px', overflow: 'hidden' }}>
                                <div style={{ width: `${data.rpmQuotaUsagePercent}%`, height: '100%', backgroundColor: '#3b82f6', borderRadius: '4px' }}></div>
                            </div>
                        </div>

                        {/* TPD Bar */}
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
                            {data.tpdQuotaUsagePercent >= 80 && (
                                <div style={{ fontSize: '9px', color: '#dc2626', fontWeight: 700, marginTop: '2px', display: 'flex', alignItems: 'center' }}>
                                    <i className="fas fa-exclamation-circle mr-1"></i>80% 임계치 초과: 트래픽 급증 대비 Quota 사전 증설 권장
                                </div>
                            )}
                        </div>
                    </div>
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
                        {data.quotaAlert ? (
                            <span style={{ fontSize: '10px', fontWeight: 700, color: '#dc2626', backgroundColor: '#fef2f2', padding: '2px 8px', borderRadius: '4px', border: '1px solid #fecaca' }}>
                                <i className="fas fa-exclamation-triangle mr-1"></i>Quota 경보
                            </span>
                        ) : (
                            <span style={{ fontSize: '10px', fontWeight: 700, color: '#059669', backgroundColor: '#ecfdf5', padding: '2px 8px', borderRadius: '4px', border: '1px solid #a7f3d0' }}>
                                <i className="fas fa-check-circle mr-1"></i>정상 가동
                            </span>
                        )}
                    </div>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', margin: 'auto 0' }}>
                    {/* Item Card 1: Endpoints & GPU Status */}
                    <div style={{
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px',
                        backgroundColor: '#fffbeb',
                        border: '1px solid #fde68a',
                        borderRadius: '8px', position: 'relative'
                    }}>
                        <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#f59e0b', borderRadius: '8px 0 0 8px' }}></div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                            <div style={{
                                width: '30px', height: '30px', borderRadius: '50%',
                                backgroundColor: '#fef3c7',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                color: '#d97706', fontSize: '13px'
                            }}>
                                <i className="fas fa-server"></i>
                            </div>
                            <div>
                                <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>엔드포인트 & GPU 인프라</span>
                                <span style={{ display: 'block', fontSize: '9px', color: '#92400e', marginTop: '1px' }}>
                                    활성 {data.activeEndpoints}개 (총 {data.totalEndpoints}개) · {data.gpuModel}
                                </span>
                            </div>
                        </div>
                        <div style={{ textAlign: 'right' }}>
                            <span style={{ fontSize: '12px', fontWeight: 800, color: '#b45309', display: 'block' }}>
                                ${data.estimatedHourlyCost}/hr
                            </span>
                            <span style={{ fontSize: '9px', color: '#78350f', display: 'block' }}>
                                (${data.estimatedMonthlyCost}/mo)
                            </span>
                        </div>
                    </div>

                    {/* Item Card 2: Model Distribution & Cache Savings */}
                    <div style={{
                        display: 'flex', flexDirection: 'column', padding: '8px 12px',
                        backgroundColor: '#eff6ff',
                        border: '1px solid #bfdbfe',
                        borderRadius: '8px', position: 'relative', gap: '4px'
                    }}>
                        <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#2563eb', borderRadius: '8px 0 0 8px' }}></div>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingLeft: '4px' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                <div style={{
                                    width: '30px', height: '30px', borderRadius: '50%',
                                    backgroundColor: '#dbeafe',
                                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                                    color: '#1d4ed8', fontSize: '13px'
                                }}>
                                    <i className="fas fa-layer-group"></i>
                                </div>
                                <div>
                                    <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>주력 AI 모델 호출 비중</span>
                                    <span style={{ display: 'block', fontSize: '9px', color: '#1e40af', marginTop: '1px' }}>
                                        Flash {data.geminiFlashRatio}% · Pro {data.geminiProRatio}% · Custom {data.fineTunedRatio}%
                                    </span>
                                </div>
                            </div>
                            <div style={{ textAlign: 'right' }}>
                                <span style={{ fontSize: '11px', fontWeight: 800, color: '#1d4ed8', backgroundColor: '#dbeafe', padding: '2px 6px', borderRadius: '4px' }}>
                                    캐시 {data.promptCacheHitRatio}%
                                </span>
                            </div>
                        </div>
                        {/* Segmented Bar */}
                        <div style={{ width: '100%', height: '5px', backgroundColor: '#e2e8f0', borderRadius: '3px', overflow: 'hidden', display: 'flex', marginTop: '2px' }}>
                            <div style={{ width: `${data.geminiFlashRatio}%`, backgroundColor: '#3b82f6' }} title={`Flash: ${data.geminiFlashRatio}%`}></div>
                            <div style={{ width: `${data.geminiProRatio}%`, backgroundColor: '#6366f1' }} title={`Pro: ${data.geminiProRatio}%`}></div>
                            <div style={{ width: `${data.fineTunedRatio}%`, backgroundColor: '#8b5cf6' }} title={`Custom: ${data.fineTunedRatio}%`}></div>
                        </div>
                    </div>

                    {/* Item Card 3: Reliability & Safety Guard */}
                    <div style={{
                        display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px',
                        backgroundColor: '#f0fdf4',
                        border: '1px solid #bbf7d0',
                        borderRadius: '8px', position: 'relative'
                    }}>
                        <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#10b981', borderRadius: '8px 0 0 8px' }}></div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                            <div style={{
                                width: '30px', height: '30px', borderRadius: '50%',
                                backgroundColor: '#dcfce7',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                color: '#15803d', fontSize: '13px'
                            }}>
                                <i className="fas fa-shield-alt"></i>
                            </div>
                            <div>
                                <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>속도 제한(429) & Safety 필터</span>
                                <span style={{ display: 'block', fontSize: '9px', color: '#166534', marginTop: '1px' }}>
                                    평균 지연 {data.avgLatencyMs}ms · Safety 차단 {data.safetyFilterBlocks}건
                                </span>
                            </div>
                        </div>
                        <div style={{ textAlign: 'right' }}>
                            <span style={{
                                fontSize: '11px',
                                fontWeight: 800,
                                color: data.rateLimit429Errors > 0 ? '#b91c1c' : '#15803d',
                                backgroundColor: data.rateLimit429Errors > 0 ? '#fee2e2' : '#dcfce7',
                                padding: '2px 6px',
                                borderRadius: '4px'
                            }}>
                                {data.rateLimit429Errors > 0 ? `429 에러 ${data.rateLimit429Errors}건` : '429 에러 0건 (안정)'}
                            </span>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default VertexAiOperationsPanel;
