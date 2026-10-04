import React, { useEffect, useState } from 'react';
import { getDirectAiMetrics, DirectAiMetricsDto } from '../services/api';

interface DirectAiUsagePanelProps {
    projectId?: string;
    targetYearMonth?: string;
    isEditMode?: boolean;
    onHideSection?: () => void;
}

const DirectAiUsagePanel: React.FC<DirectAiUsagePanelProps> = ({
    projectId,
    targetYearMonth,
    isEditMode = false,
    onHideSection
}) => {
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

    // 토큰/건수 축약 표기 (1.2M, 3.4K)
    const fmt = (n: number) => n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}K` : `${n}`;
    const maxTokenValue = Math.max(...(data.inputTokensTrend || [0]), ...(data.outputTokensTrend || [0]), 1);
    const models = data.models || [];
    const MAX_MODEL_ROWS = 6;

    const isDirectAiEmpty = !hasData || (Number(data.totalTokens || 0) === 0 && Number(data.totalPretrainedApiCalls || 0) === 0 && Number(data.totalInvocations || 0) === 0);

    const displayDates: string[] = data.dates || [];

    return (
        <div className={`direct-ai-section ${isDirectAiEmpty ? "print-hide-empty" : ""}`} style={{ marginBottom: 0 }}>
            <div className="report-card" style={{ marginBottom: 0, borderTop: '4px solid #2563eb' }}>
                {/* Header */}
                <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ display: 'flex', alignItems: 'center' }}>
                        <i className="fas fa-brain mr-2" style={{ color: '#2563eb' }}></i>
                        AI API 사용 (Vertex AI 모델 호출)
                    </span>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        {isEditMode && onHideSection && (
                            <button
                                type="button"
                                className="btn btn-sm btn-outline-danger font-weight-bold"
                                style={{ fontSize: '11px', padding: '2px 8px', borderRadius: '6px' }}
                                onClick={onHideSection}
                                title="이 섹션을 화면 및 PDF 출력 대상에서 임시로 숨깁니다. (실제 데이터는 보존됨)"
                            >
                                <i className="fas fa-eye-slash mr-1"></i>섹션 숨기기 (PDF 제외)
                            </button>
                        )}
                        {hasData && !isDirectAiEmpty ? (
                            <span style={{
                                fontSize: '10px',
                                fontWeight: 700,
                                color: '#10b981',
                                backgroundColor: '#ecfdf5',
                                padding: '2px 8px',
                                borderRadius: '12px',
                                border: '1px solid #a7f3d0'
                            }}>
                                <i className="fas fa-check-circle mr-1"></i>API 호출형 사용 중
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
                                AI API 미사용
                            </span>
                        )}
                    </div>
                </div>

                {/* 4 Summary Chips */}
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px', marginBottom: '14px' }}>
                    <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>월간 누적 토큰</span>
                        <strong style={{ fontSize: '14px', fontWeight: 800, color: '#0f172a', fontFamily: 'Pretendard, sans-serif' }}>
                            {fmt(Number(data.totalTokens || 0))} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Tokens</span>
                        </strong>
                    </div>
                    <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>모델 호출</span>
                        <strong style={{ fontSize: '14px', fontWeight: 800, color: '#2563eb', fontFamily: 'Pretendard, sans-serif' }}>
                            {Number(data.totalInvocations || 0).toLocaleString()} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Calls</span>
                        </strong>
                    </div>
                    <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>사용 모델</span>
                        <strong style={{ fontSize: '14px', fontWeight: 800, color: '#7c3aed', fontFamily: 'Pretendard, sans-serif' }}>
                            {models.length} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Models</span>
                        </strong>
                    </div>
                    <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>Pre-trained API 호출</span>
                        <strong style={{ fontSize: '14px', fontWeight: 800, color: '#059669', fontFamily: 'Pretendard, sans-serif' }}>
                            {(data.totalPretrainedApiCalls || 0).toLocaleString()} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Calls</span>
                        </strong>
                    </div>
                </div>

                {/* 2-Column Split Body */}
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                    {/* Left: Token & API Usage Trend Chart (Standardized IAM Structure & Height 170px) */}
                    <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column' }}>
                        <div className="report-card-title">
                            <span>
                                <i className="fas fa-chart-bar mr-1.5" style={{ color: '#2563eb' }}></i>월별 토큰 사용량 트렌드
                            </span>
                            <span style={{ fontSize: '10px', backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', color: '#64748b' }}>최근 4개월</span>
                        </div>

                        <div style={{ flexGrow: 1, display: 'flex', alignItems: 'flex-end', justifyContent: 'space-around', height: '170px', paddingTop: '24px', paddingBottom: '8px', borderBottom: '1px solid #e2e8f0', borderLeft: '1px solid #e2e8f0', marginLeft: '16px' }}>
                            {displayDates.map((date, idx) => {
                                const inTok = Number(data.inputTokensTrend?.[idx] || 0);
                                const outTok = Number(data.outputTokensTrend?.[idx] || 0);
                                const inHeight = inTok > 0 ? Math.max(20, Math.round((inTok / maxTokenValue) * 85)) : 0;
                                const outHeight = outTok > 0 ? Math.max(20, Math.round((outTok / maxTokenValue) * 85)) : 0;

                                return (
                                    <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '20%', height: '100%', justifyContent: 'flex-end' }}>
                                        <div style={{ display: 'flex', alignItems: 'flex-end', gap: '6px', width: '100%', justifyContent: 'center', height: '100%' }}>
                                            <div style={{
                                                width: '16px',
                                                height: `${inHeight}%`,
                                                backgroundColor: inHeight > 0 ? '#3b82f6' : 'transparent',
                                                borderRadius: '3px 3px 0 0',
                                                position: 'relative'
                                            }}>
                                                {inTok > 0 && (
                                                    <span style={{ position: 'absolute', top: '-16px', left: '50%', transform: 'translateX(-50%)', fontSize: '8px', fontWeight: 700, color: '#1e3a8a', whiteSpace: 'nowrap' }}>
                                                        {fmt(inTok)}
                                                    </span>
                                                )}
                                            </div>
                                            <div style={{
                                                width: '16px',
                                                height: `${outHeight}%`,
                                                backgroundColor: outHeight > 0 ? '#10b981' : 'transparent',
                                                borderRadius: '3px 3px 0 0',
                                                position: 'relative'
                                            }}>
                                                {outTok > 0 && (
                                                    <span style={{ position: 'absolute', top: '-16px', left: '50%', transform: 'translateX(-50%)', fontSize: '8px', fontWeight: 700, color: '#047857', whiteSpace: 'nowrap' }}>
                                                        {fmt(outTok)}
                                                    </span>
                                                )}
                                            </div>
                                        </div>
                                        <span style={{ fontSize: '11px', marginTop: '8px', color: idx === displayDates.length - 1 ? '#2563eb' : '#64748b', fontWeight: idx === displayDates.length - 1 ? 700 : 400 }}>{date}</span>
                                    </div>
                                );
                            })}
                        </div>

                        <div style={{ display: 'flex', justifyContent: 'center', gap: '16px', fontSize: '11px', marginTop: '6px' }}>
                            <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#3b82f6', marginRight: '5px' }}></span>Input 토큰
                            </span>
                            <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#10b981', marginRight: '5px' }}></span>Output 토큰
                            </span>
                        </div>
                    </div>

                    {/* Right: 모델별 호출 현황 & Pre-trained API */}
                    <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', gap: '10px' }}>
                        {/* 1. 모델별 호출 (실측, 호출 수 내림차순) */}
                        <div className="ai-model-usage" style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                            <span style={{ fontSize: '11px', fontWeight: 700, color: '#334155', display: 'block', marginBottom: '6px' }}>
                                <i className="fas fa-layer-group mr-1" style={{ color: '#8b5cf6' }}></i>모델별 호출 현황
                            </span>
                            {models.length > 0 ? (
                                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '10px' }}>
                                    <thead>
                                        <tr style={{ color: '#64748b', borderBottom: '1px solid #e2e8f0' }}>
                                            <th style={{ textAlign: 'left', padding: '3px 4px', fontWeight: 700 }}>모델</th>
                                            <th style={{ textAlign: 'right', padding: '3px 4px', fontWeight: 700 }}>호출</th>
                                            <th style={{ textAlign: 'right', padding: '3px 4px', fontWeight: 700 }}>Input</th>
                                            <th style={{ textAlign: 'right', padding: '3px 4px', fontWeight: 700 }}>Output</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {models.slice(0, MAX_MODEL_ROWS).map(m => (
                                            <tr key={`${m.publisher}/${m.model}`} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                                <td style={{ padding: '3px 4px', color: '#0f172a', fontWeight: 600 }}>
                                                    {m.model || '(알 수 없음)'} <span style={{ color: '#94a3b8', fontWeight: 400 }}>{m.publisher}</span>
                                                </td>
                                                <td style={{ padding: '3px 4px', textAlign: 'right', color: '#2563eb', fontWeight: 700 }}>{Number(m.invocations || 0).toLocaleString()}</td>
                                                <td style={{ padding: '3px 4px', textAlign: 'right', color: '#334155' }}>{fmt(Number(m.inputTokens || 0))}</td>
                                                <td style={{ padding: '3px 4px', textAlign: 'right', color: '#334155' }}>{fmt(Number(m.outputTokens || 0))}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            ) : (
                                <div style={{ fontSize: '10px', color: '#94a3b8', padding: '6px 0' }}>기준월 모델 호출 기록이 없습니다.</div>
                            )}
                            {models.length > MAX_MODEL_ROWS && (
                                <div style={{ fontSize: '9px', color: '#94a3b8', marginTop: '4px' }}>외 {models.length - MAX_MODEL_ROWS}개 모델</div>
                            )}
                        </div>

                        {/* 2. Pretrained APIs */}
                        <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                            <span style={{ fontSize: '11px', fontWeight: 700, color: '#334155', display: 'block', marginBottom: '6px' }}>
                                <i className="fas fa-microchip mr-1" style={{ color: '#059669' }}></i>Pre-trained API 호출
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
                                    <span style={{ color: '#64748b' }}>Natural Language:</span> <strong style={{ color: '#0f172a' }}>{(data.nlpApiCalls || 0).toLocaleString()}건</strong>
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
