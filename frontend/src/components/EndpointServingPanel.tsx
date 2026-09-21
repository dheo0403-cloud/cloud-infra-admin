import React, { useEffect, useState } from 'react';
import { getEndpointServingMetrics, EndpointServingMetricsDto } from '../services/api';

interface EndpointServingPanelProps {
    projectId?: string;
    targetYearMonth?: string;
}

const EndpointServingPanel: React.FC<EndpointServingPanelProps> = ({ projectId, targetYearMonth }) => {
    const [metrics, setMetrics] = useState<EndpointServingMetricsDto | null>(null);
    const [loading, setLoading] = useState<boolean>(true);

    const fetchMetrics = async () => {
        try {
            const res = await getEndpointServingMetrics(projectId, targetYearMonth);
            if (res.data) {
                setMetrics(res.data);
            }
        } catch (err) {
            console.error('Failed to fetch Endpoint Serving metrics:', err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchMetrics();
    }, [projectId, targetYearMonth]);

    const hasData = Boolean(metrics && metrics.endpoints && metrics.endpoints.length > 0);

    const data: EndpointServingMetricsDto = metrics || {
        projectId: projectId || '',
        customerName: '고객사 GCP 프로젝트',
        totalRequests7d: 0,
        currentQps: 0.0,
        avgLatencyMs: 0,
        p95LatencyMs: 0,
        p99LatencyMs: 0,
        errorRate4xxPercent: 0.0,
        errorRate5xxPercent: 0.0,
        successRatePercent: 100.0,
        totalEndpoints: 0,
        activeEndpoints: 0,
        totalAllocatedGpus: 0,
        totalEstimatedHourlyCost: 0.0,
        totalEstimatedMonthlyCost: 0.0,
        dates: [],
        dailyRequestsTrend: [],
        dailyLatencyTrend: [],
        dailyQpsTrend: [],
        endpoints: []
    };

    const maxRequestValue = Math.max(...(data.dailyRequestsTrend || [0]), 10000);
    const isEndpointEmpty = !hasData || data.totalEndpoints === 0;

    return (
        <div className={isEndpointEmpty ? "print-hide-empty" : ""} style={{ marginTop: '16px' }}>
            <div className="report-card" style={{ marginBottom: 0, borderTop: '4px solid #059669' }}>
                {/* Header */}
                <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ display: 'flex', alignItems: 'center' }}>
                        <i className="fas fa-microchip mr-2" style={{ color: '#059669' }}></i>
                        AI 엔드포인트 서빙 (Endpoint Serving) 관제
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
                                <i className="fas fa-check-circle mr-1"></i>{data.activeEndpoints}개 엔드포인트 실시간 가동 중
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
                        {/* 5 Summary Chips */}
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: '10px', marginBottom: '14px' }}>
                            <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>월간 누적 예측 요청</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#0f172a', fontFamily: 'Pretendard, sans-serif' }}>
                                    {(data.totalRequests7d || 0).toLocaleString()} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Calls</span>
                                </strong>
                            </div>
                            <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>실시간 QPS</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#2563eb', fontFamily: 'Pretendard, sans-serif' }}>
                                    {Number(data.currentQps || 0).toFixed(2)} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>req/s</span>
                                </strong>
                            </div>
                            <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>지연시간 (Avg / P95 / P99)</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#059669', fontFamily: 'Pretendard, sans-serif' }}>
                                    {data.avgLatencyMs || 0}ms <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>({data.p95LatencyMs || 0} / {data.p99LatencyMs || 0}ms)</span>
                                </strong>
                            </div>
                            <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>가속기 (GPU / TPU)</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#8b5cf6', fontFamily: 'Pretendard, sans-serif' }}>
                                    {data.totalAllocatedGpus || 0}개 <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>({data.activeEndpoints} EPs)</span>
                                </strong>
                            </div>
                            <div style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                                <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>월간 예상 서빙 비용</span>
                                <strong style={{ fontSize: '14px', fontWeight: 800, color: '#d97706', fontFamily: 'Pretendard, sans-serif' }}>
                                    ${(data.totalEstimatedMonthlyCost || 0).toFixed(1)} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>(${data.totalEstimatedHourlyCost || 0}/h)</span>
                                </strong>
                            </div>
                        </div>

                        {/* Chart & Endpoints Table Grid */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '12px' }}>
                            {/* Detailed Endpoints Table */}
                            <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '12px', overflowX: 'auto' }}>
                                <span style={{ fontSize: '11px', fontWeight: 700, color: '#334155', display: 'block', marginBottom: '8px' }}>
                                    <i className="fas fa-server mr-1" style={{ color: '#059669' }}></i>배포된 엔드포인트 인프라 및 실시간 서빙 현황
                                </span>
                                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '10px', textAlign: 'left' }}>
                                    <thead>
                                        <tr style={{ backgroundColor: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#64748b' }}>
                                            <th style={{ padding: '6px 8px', fontWeight: 700 }}>엔드포인트 명칭 / ID</th>
                                            <th style={{ padding: '6px 8px', fontWeight: 700 }}>배포 모델</th>
                                            <th style={{ padding: '6px 8px', fontWeight: 700 }}>머신타입 & GPU</th>
                                            <th style={{ padding: '6px 8px', fontWeight: 700, textAlign: 'center' }}>복제본 (Min-Max-Act)</th>
                                            <th style={{ padding: '6px 8px', fontWeight: 700, textAlign: 'right' }}>요청수 (QPS)</th>
                                            <th style={{ padding: '6px 8px', fontWeight: 700, textAlign: 'right' }}>Avg / P95 / P99</th>
                                            <th style={{ padding: '6px 8px', fontWeight: 700, textAlign: 'center' }}>GPU/CPU 부하</th>
                                            <th style={{ padding: '6px 8px', fontWeight: 700, textAlign: 'right' }}>시간당 비용</th>
                                            <th style={{ padding: '6px 8px', fontWeight: 700, textAlign: 'center' }}>상태</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {data.endpoints.map((ep, i) => (
                                            <tr key={i} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                                <td style={{ padding: '6px 8px', fontWeight: 600, color: '#0f172a' }}>
                                                    {ep.endpointName}
                                                    <span style={{ display: 'block', fontSize: '8px', color: '#94a3b8' }}>{ep.endpointId}</span>
                                                </td>
                                                <td style={{ padding: '6px 8px', color: '#334155' }}>{ep.deployedModelName}</td>
                                                <td style={{ padding: '6px 8px', color: '#475569' }}>
                                                    <span style={{ backgroundColor: '#f1f5f9', padding: '1px 4px', borderRadius: '3px', fontWeight: 600 }}>{ep.machineType}</span>
                                                    <span style={{ display: 'block', fontSize: '8px', color: '#8b5cf6', fontWeight: 700 }}>{ep.acceleratorType} × {ep.acceleratorCount}</span>
                                                </td>
                                                <td style={{ padding: '6px 8px', textAlign: 'center', color: '#334155' }}>
                                                    {ep.minReplicas} ~ {ep.maxReplicas} (<strong>{ep.currentReplicas}</strong>)
                                                </td>
                                                <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 600, color: '#0f172a' }}>
                                                    {(ep.totalRequests || 0).toLocaleString()}
                                                    <span style={{ display: 'block', fontSize: '8px', color: '#2563eb' }}>{Number(ep.qps || 0).toFixed(2)} req/s</span>
                                                </td>
                                                <td style={{ padding: '6px 8px', textAlign: 'right', color: '#059669', fontWeight: 600 }}>
                                                    {ep.avgLatencyMs}ms
                                                    <span style={{ display: 'block', fontSize: '8px', color: '#64748b' }}>{ep.p95LatencyMs} / {ep.p99LatencyMs}ms</span>
                                                </td>
                                                <td style={{ padding: '6px 8px', textAlign: 'center' }}>
                                                    <span style={{ color: '#8b5cf6', fontWeight: 700 }}>GPU {Number(ep.gpuUtilizationPercent || 0).toFixed(0)}%</span>
                                                    <span style={{ color: '#64748b', fontSize: '8px', display: 'block' }}>CPU {Number(ep.cpuUtilizationPercent || 0).toFixed(0)}%</span>
                                                </td>
                                                <td style={{ padding: '6px 8px', textAlign: 'right', fontWeight: 700, color: '#d97706' }}>
                                                    ${Number(ep.hourlyCost || 0).toFixed(2)}/h
                                                </td>
                                                <td style={{ padding: '6px 8px', textAlign: 'center' }}>
                                                    <span style={{
                                                        backgroundColor: ep.status === 'ACTIVE' ? '#ecfdf5' : '#f1f5f9',
                                                        color: ep.status === 'ACTIVE' ? '#047857' : '#64748b',
                                                        padding: '2px 6px',
                                                        borderRadius: '4px',
                                                        fontWeight: 700,
                                                        fontSize: '8px',
                                                        border: ep.status === 'ACTIVE' ? '1px solid #a7f3d0' : '1px solid #e2e8f0'
                                                    }}>
                                                        {ep.status}
                                                    </span>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    </div>
                ) : (
                    <div style={{ padding: '24px 16px', textAlign: 'center', color: '#64748b', backgroundColor: '#f8fafc', borderRadius: '8px', border: '1px dashed #e2e8f0' }}>
                        <i className="fas fa-server mr-2" style={{ color: '#94a3b8' }}></i>
                        조회 대상 연월에 배포된 24/7 Vertex AI Online Prediction 엔드포인트 또는 커스텀 GPU/TPU 모델 서버가 없습니다.
                    </div>
                )}
            </div>
        </div>
    );
};

export default EndpointServingPanel;
