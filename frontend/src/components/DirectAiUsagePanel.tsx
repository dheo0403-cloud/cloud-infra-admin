import React, { useEffect, useState } from 'react';
import { getDirectAiMetrics, DirectAiMetricsDto } from '../services/api';

interface DirectAiUsagePanelProps {
    projectId?: string;
    targetYearMonth?: string;
}

const DirectAiUsagePanel: React.FC<DirectAiUsagePanelProps> = ({ projectId, targetYearMonth }) => {
    const [metrics, setMetrics] = useState<DirectAiMetricsDto | null>(null);
    const [loading, setLoading] = useState<boolean>(true);

    const fetchMetrics = async () => {
        try {
            const res = await getDirectAiMetrics(projectId, targetYearMonth);
            if (res.data) {
                setMetrics(res.data);
            }
        } catch (err) {
            console.error('Failed to fetch Direct AI usage metrics:', err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchMetrics();
    }, [projectId, targetYearMonth]);

    const hasData = Boolean(metrics && metrics.dates && metrics.dates.length > 0);

    const data: DirectAiMetricsDto = metrics || {
        projectId: projectId || '',
        customerName: '고객사 GCP 프로젝트',
        dates: [],
        inputTokensTrend: [],
        outputTokensTrend: [],
        pretrainedApiCallsTrend: [],
        totalInputTokens: 0,
        totalOutputTokens: 0,
        totalTokens: 0,
        totalPretrainedApiCalls: 0,
        visionApiCalls: 0,
        speechApiCalls: 0,
        translationApiCalls: 0,
        nlpApiCalls: 0,
        trainingNodeHours: 0.0,
        pipelineRunsCount: 0,
        workbenchUptimeHours: 0.0,
        activeWorkbenchCount: 0,
        currentRpm: 0,
        maxRpmQuota: 1000,
        rpmQuotaUsagePercent: 0,
        currentTpd: 0,
        maxTpdQuota: 4500000,
        tpdQuotaUsagePercent: 0,
        quotaAlert: false,
        geminiFlashRatio: 0,
        geminiProRatio: 0,
        claudeRatio: 0,
        customModelRatio: 0,
        estimatedApiCost: 0,
        estimatedTrainingCost: 0,
        totalEstimatedDailyCost: 0,
        totalEstimatedMonthlyCost: 0,
        lastUpdated: new Date().toLocaleTimeString('ko-KR')
    };

    // 토큰 합산 계산 (M 단위)
    const totalTokensM = (Number(data.totalTokens || 0) / 1000000).toFixed(2);
    const maxTokenValue = Math.max(...(data.inputTokensTrend || [0]), ...(data.outputTokensTrend || [0]), 1000000);

    const isDirectAiEmpty = !hasData || (Number(data.totalTokens || 0) === 0 && Number(data.totalPretrainedApiCalls || 0) === 0 && Number(data.trainingNodeHours || 0) === 0);

    const displayDates: string[] = (data.dates && data.dates.length > 0)
        ? data.dates
        : ['26.06', '26.07', '26.08', '26.09'];

    return (
        <div className={`direct-ai-section ${isDirectAiEmpty ? "print-hide-empty" : ""}`} style={{ marginBottom: 0 }}>
            <div className="report-card" style={{ marginBottom: 0, borderTop: '4px solid #2563eb' }}>
                {/* Header */}
                <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ display: 'flex', alignItems: 'center' }}>
                        <i className="fas fa-brain mr-2" style={{ color: '#2563eb' }}></i>
                        Direct AI Usage (AI 서비스 직접 사용)
                    </span>
                    <div>
                        {hasData && !isDirectAiEmpty ? (
                            <span style={{
                                fontSize: '10px',
                                fontWeight: 700,
                                color: '#1e40af',
                                backgroundColor: '#dbeafe',
                                padding: '2px 8px',
                                borderRadius: '4px',
                                border: '1px solid #bfdbfe'
                            }}>
                                <i className="fas fa-check-circle mr-1"></i>Direct AI 서비스 활성
                            </span>
                        ) : (
                            <span style={{
                                fontSize: '10px',
                                fontWeight: 600,
                                color: '#64748b',
                                backgroundColor: '#f1f5f9',
                                padding: '2px 8px',
                                borderRadius: '4px',
                                border: '1px solid #e2e8f0'
                            }}>
                                Direct AI 미사용
                            </span>
                        )}
                    </div>
                </div>

                {/* 4 Summary Chips */}
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px', marginBottom: '14px' }}>
                    <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>월간 누적 토큰</span>
                        <strong style={{ fontSize: '14px', fontWeight: 800, color: '#0f172a', fontFamily: 'Pretendard, sans-serif' }}>
                            {totalTokensM}M <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Tokens</span>
                        </strong>
                    </div>
                    <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>Pre-trained API 호출</span>
                        <strong style={{ fontSize: '14px', fontWeight: 800, color: '#2563eb', fontFamily: 'Pretendard, sans-serif' }}>
                            {(data.totalPretrainedApiCalls || 0).toLocaleString()} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Calls</span>
                        </strong>
                    </div>
                    <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>학습 & 파이프라인</span>
                        <strong style={{ fontSize: '14px', fontWeight: 800, color: '#059669', fontFamily: 'Pretendard, sans-serif' }}>
                            {Number(data.trainingNodeHours || 0).toFixed(1)}h <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>({data.pipelineRunsCount || 0} Runs)</span>
                        </strong>
                    </div>
                    <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>월간 예상 비용</span>
                        <strong style={{ fontSize: '14px', fontWeight: 800, color: '#d97706', fontFamily: 'Pretendard, sans-serif' }}>
                            ${(data.totalEstimatedMonthlyCost || 0).toFixed(2)} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>/ 월</span>
                        </strong>
                    </div>
                </div>

                {/* 2-Column Split Body */}
                <div style={{ display: 'grid', gridTemplateColumns: '1.1fr 0.9fr', gap: '16px' }}>
                    {/* Left: Token & API Usage Trend Chart (Aligned with Cloud SQL Standard Chart Style) */}
                    <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '14px 16px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                            <span style={{ fontSize: '12px', fontWeight: 700, color: '#0f172a' }}>
                                <i className="fas fa-chart-bar mr-1.5" style={{ color: '#2563eb' }}></i>월별 토큰 및 Pre-trained API 호출 트렌드 (최근 4개월)
                            </span>
                        </div>

                        <div style={{ flexGrow: 1, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', minHeight: '115px' }}>
                            {/* Bar Area with standard dashed bottom grid line */}
                            <div style={{ display: 'flex', flexGrow: 1, minHeight: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                {displayDates.map((date, idx) => {
                                    const inTok = Number(data.inputTokensTrend?.[idx] || 0);
                                    const outTok = Number(data.outputTokensTrend?.[idx] || 0);
                                    const inHeight = inTok > 0 ? Math.max((inTok / maxTokenValue) * 100, 12) : 0;
                                    const outHeight = outTok > 0 ? Math.max((outTok / maxTokenValue) * 100, 12) : 0;

                                    return (
                                        <div key={idx} style={{ display: 'flex', gap: '4px', alignItems: 'flex-end', justifyContent: 'center', flex: 1, height: '100%' }}>
                                            {/* Input Token Bar */}
                                            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                {inTok > 0 && (
                                                    <span style={{ fontSize: '8px', fontWeight: 700, color: '#2563eb', marginBottom: '1px', lineHeight: 1 }}>
                                                        {(inTok / 1000000).toFixed(1)}M
                                                    </span>
                                                )}
                                                <div style={{
                                                    width: '12px',
                                                    height: `${inHeight}%`,
                                                    backgroundColor: inHeight > 0 ? '#3b82f6' : 'transparent',
                                                    borderRadius: '3px 3px 0 0'
                                                }}></div>
                                            </div>

                                            {/* Output Token Bar */}
                                            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                {outTok > 0 && (
                                                    <span style={{ fontSize: '8px', fontWeight: 700, color: '#059669', marginBottom: '1px', lineHeight: 1 }}>
                                                        {(outTok / 1000000).toFixed(1)}M
                                                    </span>
                                                )}
                                                <div style={{
                                                    width: '12px',
                                                    height: `${outHeight}%`,
                                                    backgroundColor: outHeight > 0 ? '#10b981' : 'transparent',
                                                    borderRadius: '3px 3px 0 0'
                                                }}></div>
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>

                            {/* X-axis Date Labels */}
                            <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                {displayDates.map((m, idx) => (
                                    <span key={idx} style={{ flex: 1, textAlign: 'center', fontWeight: idx === displayDates.length - 1 ? 700 : 400, color: idx === displayDates.length - 1 ? '#2563eb' : '#64748b' }}>
                                        {m}
                                    </span>
                                ))}
                            </div>

                            {/* Standard Bottom-Center Legend matching Cloud SQL / PD style */}
                            <div style={{ display: 'flex', justifyContent: 'center', gap: '14px', fontSize: '11px', marginTop: '6px' }}>
                                <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                    <span style={{ display: 'inline-block', width: '10px', height: '10px', borderRadius: '2px', backgroundColor: '#3b82f6', marginRight: '4px' }}></span>
                                    Input 토큰
                                </span>
                                <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                    <span style={{ display: 'inline-block', width: '10px', height: '10px', borderRadius: '2px', backgroundColor: '#10b981', marginRight: '4px' }}></span>
                                    Output 토큰
                                </span>
                            </div>
                        </div>
                    </div>

                    {/* Right: AI Workload Breakdown & Quota */}
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                        {/* 1. Model Ratio Breakdown */}
                        <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                            <span style={{ fontSize: '11px', fontWeight: 700, color: '#334155', display: 'block', marginBottom: '8px' }}>
                                <i className="fas fa-layer-group mr-1" style={{ color: '#8b5cf6' }}></i>AI 모델별 호출 비중
                            </span>
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                                <div>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', color: '#475569', marginBottom: '2px' }}>
                                        <span>Gemini Flash</span>
                                        <strong style={{ color: '#3b82f6' }}>{Number(data.geminiFlashRatio || 0).toFixed(1)}%</strong>
                                    </div>
                                    <div style={{ width: '100%', height: '5px', backgroundColor: '#e2e8f0', borderRadius: '3px', overflow: 'hidden' }}>
                                        <div style={{ width: `${Number(data.geminiFlashRatio || 0)}%`, height: '100%', backgroundColor: '#3b82f6' }}></div>
                                    </div>
                                </div>
                                <div>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', color: '#475569', marginBottom: '2px' }}>
                                        <span>Gemini Pro</span>
                                        <strong style={{ color: '#8b5cf6' }}>{Number(data.geminiProRatio || 0).toFixed(1)}%</strong>
                                    </div>
                                    <div style={{ width: '100%', height: '5px', backgroundColor: '#e2e8f0', borderRadius: '3px', overflow: 'hidden' }}>
                                        <div style={{ width: `${Number(data.geminiProRatio || 0)}%`, height: '100%', backgroundColor: '#8b5cf6' }}></div>
                                    </div>
                                </div>
                                <div>
                                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '10px', color: '#475569', marginBottom: '2px' }}>
                                        <span>Claude / Custom</span>
                                        <strong style={{ color: '#d97706' }}>{(Number(data.claudeRatio || 0) + Number(data.customModelRatio || 0)).toFixed(1)}%</strong>
                                    </div>
                                    <div style={{ width: '100%', height: '5px', backgroundColor: '#e2e8f0', borderRadius: '3px', overflow: 'hidden' }}>
                                        <div style={{ width: `${Number(data.claudeRatio || 0) + Number(data.customModelRatio || 0)}%`, height: '100%', backgroundColor: '#d97706' }}></div>
                                    </div>
                                </div>
                            </div>
                        </div>

                        {/* 2. Pretrained APIs & Workload Resources Grid */}
                        <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                            <span style={{ fontSize: '11px', fontWeight: 700, color: '#334155', display: 'block', marginBottom: '6px' }}>
                                <i className="fas fa-microchip mr-1" style={{ color: '#059669' }}></i>Pre-trained & 워크로드 리소스
                            </span>
                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px', fontSize: '10px' }}>
                                <div style={{ backgroundColor: '#f8fafc', padding: '5px 8px', borderRadius: '4px', border: '1px solid #f1f5f9' }}>
                                    <span style={{ color: '#64748b' }}>Vision API:</span> <strong style={{ color: '#0f172a' }}>{(data.visionApiCalls || 0).toLocaleString()}건</strong>
                                </div>
                                <div style={{ backgroundColor: '#f8fafc', padding: '5px 8px', borderRadius: '4px', border: '1px solid #f1f5f9' }}>
                                    <span style={{ color: '#64748b' }}>Speech API:</span> <strong style={{ color: '#0f172a' }}>{(data.speechApiCalls || 0).toLocaleString()}건</strong>
                                </div>
                                <div style={{ backgroundColor: '#f8fafc', padding: '5px 8px', borderRadius: '4px', border: '1px solid #f1f5f9' }}>
                                    <span style={{ color: '#64748b' }}>Translation:</span> <strong style={{ color: '#0f172a' }}>{(data.translationApiCalls || 0).toLocaleString()}건</strong>
                                </div>
                                <div style={{ backgroundColor: '#f8fafc', padding: '5px 8px', borderRadius: '4px', border: '1px solid #f1f5f9' }}>
                                    <span style={{ color: '#64748b' }}>Workbench:</span> <strong style={{ color: '#0f172a' }}>{data.activeWorkbenchCount || 0}대 ({Number(data.workbenchUptimeHours || 0).toFixed(0)}h)</strong>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default DirectAiUsagePanel;
