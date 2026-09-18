import React, { useEffect, useState } from 'react';
import { getVertexEndpointMetrics, VertexEndpointMetricsDto } from '../services/api';

interface VertexEndpointOperationsPanelProps {
    projectId?: string;
    targetYearMonth?: string;
}

const VertexEndpointOperationsPanel: React.FC<VertexEndpointOperationsPanelProps> = ({ projectId, targetYearMonth }) => {
    const [metrics, setMetrics] = useState<VertexEndpointMetricsDto | null>(null);
    const [loading, setLoading] = useState<boolean>(true);

    const fetchMetrics = async () => {
        try {
            const res = await getVertexEndpointMetrics(projectId, targetYearMonth);
            if (res.data) {
                setMetrics(res.data);
            }
        } catch (err) {
            console.error('Failed to fetch Vertex AI endpoint operations metrics:', err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchMetrics();
    }, [projectId, targetYearMonth]);

    const hasData = Boolean(metrics && metrics.endpoints && metrics.endpoints.length > 0);

    const data: VertexEndpointMetricsDto = metrics || {
        projectId: projectId || '',
        customerName: '고객사 GCP 프로젝트',
        totalPredictRequests7d: 0,
        avgLatencyMs: 0,
        p95LatencyMs: 0,
        successRatePercent: 100.0,
        totalEndpoints: 0,
        activeEndpoints: 0,
        totalEstimatedHourlyCost: 0.0,
        totalEstimatedMonthlyCost: 0.0,
        dates: [],
        dailyRequestsTrend: [],
        dailyLatencyTrend: [],
        endpoints: []
    };

    // 차트 최대값 계산
    const maxRequestValue = Math.max(...(data.dailyRequestsTrend || [0]), 100000);

    const isEndpointEmpty = !hasData || data.totalEndpoints === 0;

    return (
        <div className={isEndpointEmpty ? "print-hide-empty" : ""} style={{ marginTop: '16px' }}>
            <div className="report-card" style={{ marginBottom: 0 }}>
                {/* Header */}
                <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ display: 'flex', alignItems: 'center' }}>
                        <i className="fas fa-microchip mr-2" style={{ color: '#059669' }}></i>
                        Vertex AI 엔드포인트 호출 트렌드 및 인프라 관제
                    </span>
                    <div>
                        {hasData && data.totalEndpoints > 0 ? (
                            <span style={{
                                fontSize: '10px',
                                fontWeight: 700,
                                color: '#047857',
                                backgroundColor: '#ecfdf5',
                                padding: '2px 8px',
                                borderRadius: '4px',
                                border: '1px solid #a7f3d0'
                            }}>
                                <i className="fas fa-check-circle mr-1"></i>{data.activeEndpoints}개 엔드포인트 활성 가동
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
                                엔드포인트 미사용
                            </span>
                        )}
                    </div>
                </div>

                {hasData && data.totalEndpoints > 0 ? (
                    <div>
                        {/* 4 Summary Chips */}
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px', marginBottom: '14px' }}>
                            <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>7일 총 예측 호출</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#0f172a', fontFamily: 'Pretendard, sans-serif' }}>
                                    {(data.totalPredictRequests7d || 0).toLocaleString()} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Calls</span>
                                </strong>
                            </div>

                            <div style={{ backgroundColor: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#166534', fontWeight: 600 }}>평균 추론 지연시간</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#15803d', fontFamily: 'Pretendard, sans-serif' }}>
                                    {data.avgLatencyMs} ms <span style={{ fontSize: '9px', fontWeight: 500, color: '#86efac' }}>(P95: {data.p95LatencyMs}ms)</span>
                                </strong>
                            </div>

                            <div style={{ backgroundColor: '#eff6ff', border: '1px solid #dbeafe', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#1e40af', fontWeight: 600 }}>추론 성공률</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#1d4ed8', fontFamily: 'Pretendard, sans-serif' }}>
                                    {data.successRatePercent}% <span style={{ fontSize: '9px', fontWeight: 500, color: '#93c5fd' }}>(99.9% 가용)</span>
                                </strong>
                            </div>

                            <div style={{ backgroundColor: '#fffbeb', border: '1px solid #fde68a', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#92400e', fontWeight: 600 }}>시간당 인프라 비용</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#b45309', fontFamily: 'Pretendard, sans-serif' }}>
                                    ${data.totalEstimatedHourlyCost}/hr <span style={{ fontSize: '9px', fontWeight: 500, color: '#f59e0b' }}>(${data.totalEstimatedMonthlyCost}/mo)</span>
                                </strong>
                            </div>
                        </div>

                        {/* Chart & Endpoints Grid (2 Columns Layout) */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.2fr', gap: '14px', alignItems: 'stretch' }}>
                            {/* Left: Daily Predict Requests & Latency Trend Chart */}
                            <div style={{
                                backgroundColor: '#f8fafc',
                                border: '1px solid #e2e8f0',
                                borderRadius: '8px',
                                padding: '12px',
                                display: 'flex',
                                flexDirection: 'column',
                                justifyContent: 'space-between'
                            }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                                    <span style={{ fontSize: '11px', fontWeight: 700, color: '#334155' }}>
                                        <i className="fas fa-chart-bar mr-1" style={{ color: '#059669' }}></i>일별 예측 요청수 & 지연시간 트렌드
                                    </span>
                                    <span style={{ fontSize: '9px', color: '#64748b' }}>최근 7일간 추이</span>
                                </div>

                                <div style={{ height: '120px', display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', borderBottom: '1px dashed #cbd5e1', paddingBottom: '4px' }}>
                                    {data.dates.map((date, idx) => {
                                        const reqs = data.dailyRequestsTrend[idx] || 0;
                                        const lat = data.dailyLatencyTrend[idx] || 0;
                                        const barHeightPercent = Math.max(14, Math.min(80, (reqs / maxRequestValue) * 80));

                                        const formatCallsLabel = (val: number) => {
                                            if (val >= 1000000) return `${(val / 1000000).toFixed(1)}M`;
                                            if (val >= 1000) return `${(val / 1000).toFixed(1)}K`;
                                            return val > 0 ? `${val}` : '0';
                                        };

                                        return (
                                            <div key={idx} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', marginBottom: '2px' }}>
                                                    <span style={{ fontSize: '8px', fontWeight: 800, color: '#047857', lineHeight: 1.1 }}>
                                                        {formatCallsLabel(reqs)}
                                                    </span>
                                                    <span style={{ fontSize: '7.5px', fontWeight: 600, color: '#0284c7', lineHeight: 1.1 }}>
                                                        {lat}ms
                                                    </span>
                                                </div>
                                                <div
                                                    title={`${date} - 예측 호출: ${reqs.toLocaleString()}건 (${formatCallsLabel(reqs)}), 평균 지연시간: ${lat}ms`}
                                                    style={{
                                                        width: '18px',
                                                        height: `${barHeightPercent}%`,
                                                        backgroundColor: '#10b981',
                                                        borderRadius: '4px 4px 0 0',
                                                        transition: 'height 0.3s'
                                                    }}
                                                ></div>
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
                                <div style={{ display: 'flex', justifyContent: 'center', gap: '16px', fontSize: '10px', marginTop: '8px' }}>
                                    <span style={{ color: '#334155', fontWeight: 600, display: 'flex', alignItems: 'center' }}>
                                        <span style={{ display: 'inline-block', width: '8px', height: '8px', borderRadius: '2px', backgroundColor: '#10b981', marginRight: '4px' }}></span>예측 요청수 (Predict Calls)
                                    </span>
                                    <span style={{ color: '#059669', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                        <i className="fas fa-stopwatch mr-1"></i>평균 지연시간 (ms)
                                    </span>
                                </div>
                            </div>

                            {/* Right: Deployed Endpoint Infrastructure List */}
                            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                                <div style={{ fontSize: '11px', fontWeight: 700, color: '#334155', marginBottom: '2px' }}>
                                    <i className="fas fa-server mr-1" style={{ color: '#2563eb' }}></i>배포된 엔드포인트 인프라 목록 ({data.endpoints.length}개)
                                </div>

                                {data.endpoints.map((ep, idx) => (
                                    <div key={idx} style={{
                                        backgroundColor: '#ffffff',
                                        border: '1px solid #e2e8f0',
                                        borderRadius: '8px',
                                        padding: '8px 12px',
                                        boxShadow: '0 1px 2px rgba(0,0,0,0.03)',
                                        position: 'relative'
                                    }}>
                                        <div style={{
                                            position: 'absolute',
                                            left: 0,
                                            top: 0,
                                            width: '4px',
                                            height: '100%',
                                            backgroundColor: ep.status === 'ACTIVE' ? '#10b981' : '#f59e0b',
                                            borderRadius: '8px 0 0 8px'
                                        }}></div>

                                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', paddingLeft: '4px' }}>
                                            <div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                                                    <span style={{ fontSize: '11.5px', fontWeight: 700, color: '#0f172a' }}>{ep.endpointName}</span>
                                                    <span style={{
                                                        fontSize: '8.5px',
                                                        fontWeight: 700,
                                                        color: '#065f46',
                                                        backgroundColor: '#d1fae5',
                                                        padding: '1px 5px',
                                                        borderRadius: '3px'
                                                    }}>
                                                        {ep.status}
                                                    </span>
                                                </div>
                                                <span style={{ display: 'block', fontSize: '9.5px', color: '#64748b', marginTop: '1px' }}>
                                                    <i className="fas fa-cube mr-1" style={{ color: '#4f46e5' }}></i>모델: {ep.deployedModelName}
                                                </span>
                                            </div>

                                            <div style={{ textAlign: 'right' }}>
                                                <span style={{ fontSize: '11px', fontWeight: 800, color: '#0f172a', display: 'block' }}>
                                                    ${ep.estimatedHourlyCost}/hr
                                                </span>
                                                <span style={{ fontSize: '9px', color: '#64748b' }}>
                                                    Replicas: {ep.activeReplicaCount}/{ep.maxReplicaCount}
                                                </span>
                                            </div>
                                        </div>

                                        {/* Infrastructure Specs & Utilization */}
                                        <div style={{
                                            marginTop: '6px',
                                            paddingTop: '6px',
                                            borderTop: '1px solid #f1f5f9',
                                            display: 'flex',
                                            justifyContent: 'space-between',
                                            alignItems: 'center',
                                            fontSize: '9.5px',
                                            paddingLeft: '4px'
                                        }}>
                                            <span style={{ color: '#475569' }}>
                                                <i className="fas fa-microchip mr-1" style={{ color: '#059669' }}></i>
                                                {ep.machineType} {ep.acceleratorType !== 'CPU_ONLY' ? `· ${ep.acceleratorType}` : '· CPU'}
                                            </span>

                                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                                {ep.acceleratorType !== 'CPU_ONLY' && (
                                                    <span style={{ color: '#b45309', fontWeight: 600 }}>
                                                        GPU 부하: {ep.gpuUtilizationPercent}%
                                                    </span>
                                                )}
                                                <span style={{ color: '#2563eb', fontWeight: 600 }}>
                                                    지연: {ep.avgLatencyMs}ms (P95: {ep.p95LatencyMs}ms)
                                                </span>
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                ) : (
                    <div style={{
                        display: 'flex',
                        flexDirection: 'column',
                        alignItems: 'center',
                        justifyContent: 'center',
                        minHeight: '140px',
                        backgroundColor: '#f8fafc',
                        borderRadius: '8px',
                        border: '1px dashed #cbd5e1',
                        padding: '20px'
                    }}>
                        <i className="fas fa-microchip" style={{ fontSize: '24px', color: '#94a3b8', marginBottom: '8px' }}></i>
                        <span style={{ fontSize: '12px', fontWeight: 700, color: '#475569' }}>배포된 Vertex AI 엔드포인트가 없습니다.</span>
                        <span style={{ fontSize: '10px', color: '#94a3b8', marginTop: '4px' }}>
                            선택된 연월({targetYearMonth || '당월'})에 커스텀 모델 서빙 엔드포인트 호출 내역이 존재하지 않습니다.
                        </span>
                    </div>
                )}
            </div>
        </div>
    );
};

export default VertexEndpointOperationsPanel;
