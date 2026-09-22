import React, { useEffect, useState } from 'react';
import { getBigQueryOptimizationMetrics, BigQueryOptimizationDto } from '../services/api';

interface BigQueryOptimizationPanelProps {
    projectId?: string;
    targetYearMonth?: string;
    isEditMode?: boolean;
    onHideSection?: () => void;
}

const BigQueryOptimizationPanel: React.FC<BigQueryOptimizationPanelProps> = ({
    projectId,
    targetYearMonth,
    isEditMode = false,
    onHideSection
}) => {
    const [metrics, setMetrics] = useState<BigQueryOptimizationDto | null>(null);
    const [loading, setLoading] = useState<boolean>(true);

    const fetchMetrics = async () => {
        try {
            const res = await getBigQueryOptimizationMetrics(projectId, targetYearMonth);
            if (res.data) {
                setMetrics(res.data);
            }
        } catch (err) {
            console.error('Failed to fetch BigQuery optimization metrics:', err);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchMetrics();
    }, [projectId, targetYearMonth]);

    const data: BigQueryOptimizationDto = metrics || {
        projectId: projectId || '',
        customerName: '고객사 GCP 프로젝트',
        targetYearMonth: targetYearMonth || '2026-09',
        dates: ['26.06', '26.07', '26.08', '26.09'],
        dataProcessedTbTrend: [0, 0, 0, 0],
        jobCountTrend: [0, 0, 0, 0],
        currentMonthProcessedTb: 0.0,
        currentMonthJobCount: 0,
        totalLogicalStorageGb: 0.0,
        totalPhysicalStorageGb: 0.0,
        totalPhysicalStorageTb: 0.0,
        highCostQueries: [],
        maxSlotUsage: 0.0,
        minSlotUsage: 0.0,
        avgSlotUsage: 0.0,
        slotHealthStatus: '정상',
        longDurationQueries: [],
        lastUpdated: new Date().toLocaleTimeString('ko-KR')
    };

    const hasData = Boolean(
        data.currentMonthProcessedTb > 0 ||
        data.currentMonthJobCount > 0 ||
        (data.highCostQueries && data.highCostQueries.length > 0) ||
        (data.longDurationQueries && data.longDurationQueries.length > 0)
    );

    const maxTb = Math.max(...(data.dataProcessedTbTrend || [0]), 1.0);
    const displayDates = (data.dates && data.dates.length > 0) ? data.dates : ['26.06', '26.07', '26.08', '26.09'];

    return (
        <div className={`bq-optimization-section ${!hasData ? "print-hide-empty" : ""}`} style={{ marginTop: '16px', marginBottom: 0 }}>
            <div className="report-card" style={{ borderTop: '4px solid #2563eb', marginBottom: 0 }}>
                {/* Header Title */}
                <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderBottom: '1px solid #e2e8f0', paddingBottom: '8px', marginBottom: '12px' }}>
                    <span style={{ fontSize: '13.5px', fontWeight: 800, color: '#0f172a', display: 'flex', alignItems: 'center' }}>
                        <i className="fas fa-database mr-2" style={{ color: '#2563eb' }}></i>
                        BigQuery 성능 및 비용 최적화 분석 (BigQuery Optimization)
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
                        <span style={{ fontSize: '10.5px', color: '#10b981', fontWeight: 600, backgroundColor: '#ecfdf5', padding: '2px 8px', borderRadius: '12px', border: '1px solid #a7f3d0' }}>
                            <i className="fas fa-check-circle mr-1"></i>INFORMATION_SCHEMA 분석 활성
                        </span>
                    </div>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>

                    {/* ========================================================================= */}
                    {/* [영역 1] 월별 리소스 및 스토리지 현황 (트렌드 모니터링)                      */}
                    {/* ========================================================================= */}
                    <div>
                        <span style={{ fontSize: '11.5px', fontWeight: 700, color: '#1e293b', display: 'block', marginBottom: '6px' }}>
                            <i className="fas fa-chart-bar mr-1" style={{ color: '#3b82f6' }}></i>1. 월별 리소스 및 스토리지 현황 (트렌드 모니터링)
                        </span>

                        <div style={{ display: 'grid', gridTemplateColumns: '1.1fr 0.9fr', gap: '16px' }}>
                            {/* Left: 4-Month Processed TB & Job Count Chart (Standardized Layout) */}
                            <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '12px 14px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                                    <span style={{ fontSize: '11px', fontWeight: 700, color: '#0f172a' }}>
                                        월간 데이터 사용량(TB) & Job Count 추이
                                    </span>
                                    <span style={{ fontSize: '10px', color: '#64748b' }}>
                                        당월: <strong>{Number(data.currentMonthProcessedTb || 0).toFixed(2)} TB</strong> / <strong>{(data.currentMonthJobCount || 0).toLocaleString()} Jobs</strong>
                                    </span>
                                </div>

                                {/* Bar Chart Area with Standard Fixed Width and Proportions */}
                                <div style={{ flexGrow: 1, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', minHeight: '115px' }}>
                                    <div style={{ display: 'flex', flexGrow: 1, minHeight: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                        {displayDates.map((dateStr, idx) => {
                                            const tbVal = data.dataProcessedTbTrend?.[idx] || 0;
                                            const jcVal = data.jobCountTrend?.[idx] || 0;
                                            const isZero = tbVal <= 0 && jcVal <= 0;
                                            const heightPercent = isZero ? 0 : Math.max(12, Math.min(100, Math.round((tbVal / maxTb) * 85)));

                                            return (
                                                <div key={idx} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                    <span style={{ fontSize: '8.5px', fontWeight: 700, color: '#2563eb', marginBottom: '2px', visibility: isZero ? 'hidden' : 'visible' }}>
                                                        {Number(tbVal).toFixed(2)}TB
                                                    </span>
                                                    <div style={{
                                                        width: '22px',
                                                        height: `${heightPercent}%`,
                                                        background: isZero ? 'transparent' : 'linear-gradient(180deg, #3b82f6 0%, #1d4ed8 100%)',
                                                        borderRadius: '4px 4px 0 0',
                                                        opacity: isZero ? 0 : 1,
                                                        transition: 'height 0.3s ease'
                                                    }}></div>
                                                    <span style={{ fontSize: '8px', color: '#64748b', marginTop: '2px', visibility: isZero ? 'hidden' : 'visible' }}>
                                                        {jcVal.toLocaleString()}건
                                                    </span>
                                                </div>
                                            );
                                        })}
                                    </div>

                                    {/* X-Axis Labels */}
                                    <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                        {displayDates.map((d, i) => (
                                            <span key={i} style={{ flex: 1, textAlign: 'center', fontWeight: 600 }}>{d}</span>
                                        ))}
                                    </div>

                                    {/* Standard Bottom-Center Legend */}
                                    <div style={{ display: 'flex', justifyContent: 'center', gap: '14px', fontSize: '10.5px', marginTop: '6px' }}>
                                        <span style={{ color: '#1e293b', fontWeight: 600, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '10px', height: '10px', borderRadius: '2px', backgroundColor: '#2563eb', marginRight: '4px' }}></span>
                                            데이터 사용량 (TB)
                                        </span>
                                        <span style={{ color: '#1e293b', fontWeight: 600, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '10px', height: '2px', backgroundColor: '#64748b', marginRight: '4px' }}></span>
                                            실행 Job 수 (건)
                                        </span>
                                    </div>
                                </div>
                            </div>

                            {/* Right: Storage Capacity Summary Cards */}
                            <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '12px 14px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                                <span style={{ fontSize: '11px', fontWeight: 700, color: '#0f172a', marginBottom: '8px', display: 'block' }}>
                                    전체 데이터셋 스토리지 용량
                                </span>

                                {(() => {
                                    const hasLogical = (data.totalLogicalStorageGb || 0) > 0;
                                    const hasPhysical = (data.totalPhysicalStorageGb || 0) > 0;

                                    return (
                                        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                                            <div style={{
                                                backgroundColor: hasLogical ? '#eff6ff' : '#f8fafc',
                                                padding: '8px 10px',
                                                borderRadius: '6px',
                                                border: hasLogical ? '1px solid #bfdbfe' : '1px solid #e2e8f0'
                                            }}>
                                                <span style={{ fontSize: '10px', color: hasLogical ? '#1e40af' : '#64748b', display: 'block', fontWeight: 600 }}>
                                                    <i className="fas fa-bolt mr-1"></i>논리적 스토리지 (활성 요금 기준)
                                                </span>
                                                <strong style={{ fontSize: '14px', fontWeight: 800, color: hasLogical ? '#1d4ed8' : '#64748b' }}>
                                                    {Number(data.totalLogicalStorageGb || 0).toFixed(1)} <span style={{ fontSize: '10px', fontWeight: 500, color: hasLogical ? '#1d4ed8' : '#94a3b8' }}>GB</span>
                                                </strong>
                                            </div>

                                            <div style={{
                                                backgroundColor: hasPhysical ? '#f0fdf4' : '#f8fafc',
                                                padding: '8px 10px',
                                                borderRadius: '6px',
                                                border: hasPhysical ? '1px solid #bbf7d0' : '1px solid #e2e8f0'
                                            }}>
                                                <span style={{ fontSize: '10px', color: hasPhysical ? '#166534' : '#64748b', display: 'block', fontWeight: 600 }}>
                                                    <i className="fas fa-archive mr-1"></i>물리적 스토리지 (장기 요금 기준)
                                                </span>
                                                <strong style={{ fontSize: '14px', fontWeight: 800, color: hasPhysical ? '#15803d' : '#64748b' }}>
                                                    {Number(data.totalPhysicalStorageGb || 0).toFixed(1)} <span style={{ fontSize: '10px', fontWeight: 500, color: hasPhysical ? '#15803d' : '#94a3b8' }}>GB</span>
                                                    <span style={{ fontSize: '10px', fontWeight: 500, color: hasPhysical ? '#64748b' : '#94a3b8', marginLeft: '4px' }}>({Number(data.totalPhysicalStorageTb || 0).toFixed(3)} TB)</span>
                                                </strong>
                                            </div>
                                        </div>
                                    );
                                })()}
                                <span style={{ fontSize: '9px', color: '#94a3b8', marginTop: '6px', display: 'block' }}>
                                    * 90일 이상 미수정 테이블은 장기 스토리지 할인 요율 자동 적용
                                </span>
                            </div>
                        </div>
                    </div>

                    {/* ========================================================================= */}
                    {/* [영역 2] 고비용 쿼리 분석 (TOP 10 비용 최적화)                                */}
                    {/* ========================================================================= */}
                    <div>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                            <span style={{ fontSize: '11.5px', fontWeight: 700, color: '#1e293b' }}>
                                <i className="fas fa-coins mr-1" style={{ color: '#d97706' }}></i>2. 고비용 쿼리 분석 (가장 많은 데이터 비용을 사용한 TOP 10)
                            </span>
                            <span style={{ fontSize: '9.5px', color: '#64748b' }}>
                                * 온디맨드 쿼리 요금($6.25/TB) 기준 정렬
                            </span>
                        </div>

                        <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '8px', overflowX: 'auto' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '10px', textAlign: 'left' }}>
                                <thead>
                                    <tr style={{ backgroundColor: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#64748b' }}>
                                        <th style={{ padding: '5px 6px', width: '32px', textAlign: 'center', fontWeight: 700 }}>순위</th>
                                        <th style={{ padding: '5px 6px', width: '65px', fontWeight: 700 }}>실행 일자</th>
                                        <th style={{ padding: '5px 6px', width: '130px', fontWeight: 700 }}>실행 계정 (IAM)</th>
                                        <th style={{ padding: '5px 6px', fontWeight: 700 }}>SQL 쿼리문</th>
                                        <th style={{ padding: '5px 6px', width: '70px', textAlign: 'right', fontWeight: 700 }}>스캔량 (GB)</th>
                                        <th style={{ padding: '5px 6px', width: '65px', textAlign: 'right', fontWeight: 700 }}>예상 비용</th>
                                        <th style={{ padding: '5px 6px', width: '65px', textAlign: 'right', fontWeight: 700 }}>실행 시간</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {data.highCostQueries && data.highCostQueries.length > 0 ? (
                                        data.highCostQueries.map((item, idx) => (
                                            <tr key={idx} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                                <td style={{ padding: '5px 6px', textAlign: 'center', fontWeight: 800, color: idx < 3 ? '#dc2626' : '#64748b' }}>
                                                    {item.rank}
                                                </td>
                                                <td style={{ padding: '5px 6px', color: '#475569' }}>{item.createdDate}</td>
                                                <td style={{ padding: '5px 6px', color: '#334155', maxWidth: '130px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={item.userEmail}>
                                                    {item.userEmail}
                                                </td>
                                                <td style={{ padding: '5px 6px', color: '#0f172a', fontFamily: 'monospace', maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={item.query}>
                                                    <span style={{ backgroundColor: '#f1f5f9', padding: '1px 3px', borderRadius: '3px', fontWeight: 600, marginRight: '4px', fontSize: '8.5px', color: '#2563eb' }}>
                                                        {item.statementType || 'SELECT'}
                                                    </span>
                                                    {item.query}
                                                </td>
                                                <td style={{ padding: '5px 6px', textAlign: 'right', fontWeight: 700, color: '#dc2626' }}>
                                                    {Number(item.bytesProcessedGb || 0).toFixed(1)} GB
                                                </td>
                                                <td style={{ padding: '5px 6px', textAlign: 'right', fontWeight: 800, color: '#b45309' }}>
                                                    ${Number(item.estimatedCostUsd || 0).toFixed(2)}
                                                </td>
                                                <td style={{ padding: '5px 6px', textAlign: 'right', color: '#475569' }}>
                                                    {Number(item.executionTimeSeconds || 0).toFixed(1)}초
                                                </td>
                                            </tr>
                                        ))
                                    ) : (
                                        <tr>
                                            <td colSpan={7} style={{ padding: '12px', textAlign: 'center', color: '#94a3b8' }}>
                                                조회 대상 연월에 기록된 고비용 쿼리 내역이 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>

                    {/* ========================================================================= */}
                    {/* [영역 3] 쿼리 성능 및 병목 현상 분석 (성능 최적화 & 슬롯 분석)                */}
                    {/* ========================================================================= */}
                    <div>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                            <span style={{ fontSize: '11.5px', fontWeight: 700, color: '#1e293b' }}>
                                <i className="fas fa-tachometer-alt mr-1" style={{ color: '#059669' }}></i>3. 쿼리 성능 및 병목 현상 분석 (실행 시간 TOP 10 & 슬롯 분석)
                            </span>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                <span style={{ fontSize: '9.5px', color: '#64748b' }}>
                                    당월 슬롯 사용량: 최대 <strong>{data.maxSlotUsage || 0}</strong> / 평균 <strong>{data.avgSlotUsage || 0}</strong> Slots
                                </span>
                                <span style={{
                                    fontSize: '9.5px',
                                    fontWeight: 700,
                                    color: (data.maxSlotUsage || 0) <= 0 ? '#475569' : ((data.maxSlotUsage || 0) > 800 ? '#b91c1c' : '#15803d'),
                                    backgroundColor: (data.maxSlotUsage || 0) <= 0 ? '#f1f5f9' : ((data.maxSlotUsage || 0) > 800 ? '#fee2e2' : '#dcfce7'),
                                    padding: '1px 7px',
                                    borderRadius: '8px',
                                    border: (data.maxSlotUsage || 0) <= 0 ? '1px solid #cbd5e1' : ((data.maxSlotUsage || 0) > 800 ? '1px solid #fca5a5' : '1px solid #86efac')
                                }}>
                                    <i className={`fas ${(data.maxSlotUsage || 0) <= 0 ? 'fa-minus-circle' : ((data.maxSlotUsage || 0) > 800 ? 'fa-exclamation-triangle' : 'fa-check')} mr-1`}></i>
                                    {data.slotHealthStatus || ((data.maxSlotUsage || 0) <= 0 ? '정상 (데이터 없음)' : '정상 (여유 슬롯 확보)')}
                                </span>
                            </div>
                        </div>

                        <div style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '8px', overflowX: 'auto' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '10px', textAlign: 'left' }}>
                                <thead>
                                    <tr style={{ backgroundColor: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#64748b' }}>
                                        <th style={{ padding: '5px 6px', width: '32px', textAlign: 'center', fontWeight: 700 }}>순위</th>
                                        <th style={{ padding: '5px 6px', width: '65px', fontWeight: 700 }}>실행 일자</th>
                                        <th style={{ padding: '5px 6px', width: '130px', fontWeight: 700 }}>실행 계정 (IAM)</th>
                                        <th style={{ padding: '5px 6px', fontWeight: 700 }}>SQL 쿼리문</th>
                                        <th style={{ padding: '5px 6px', width: '75px', textAlign: 'right', fontWeight: 700 }}>실행 소요시간</th>
                                        <th style={{ padding: '5px 6px', width: '65px', textAlign: 'right', fontWeight: 700 }}>평균 슬롯</th>
                                        <th style={{ padding: '5px 6px', width: '65px', textAlign: 'right', fontWeight: 700 }}>스캔량</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {data.longDurationQueries && data.longDurationQueries.length > 0 ? (
                                        data.longDurationQueries.map((item, idx) => (
                                            <tr key={idx} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                                <td style={{ padding: '5px 6px', textAlign: 'center', fontWeight: 800, color: idx < 3 ? '#ea580c' : '#64748b' }}>
                                                    {item.rank}
                                                </td>
                                                <td style={{ padding: '5px 6px', color: '#475569' }}>{item.createdDate}</td>
                                                <td style={{ padding: '5px 6px', color: '#334155', maxWidth: '130px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={item.userEmail}>
                                                    {item.userEmail}
                                                </td>
                                                <td style={{ padding: '5px 6px', color: '#0f172a', fontFamily: 'monospace', maxWidth: '300px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={item.query}>
                                                    <span style={{ backgroundColor: '#f1f5f9', padding: '1px 3px', borderRadius: '3px', fontWeight: 600, marginRight: '4px', fontSize: '8.5px', color: '#059669' }}>
                                                        {item.statementType || 'SELECT'}
                                                    </span>
                                                    {item.query}
                                                </td>
                                                <td style={{ padding: '5px 6px', textAlign: 'right', fontWeight: 700, color: '#ea580c' }}>
                                                    {Number(item.executionTimeSeconds || 0).toFixed(1)}초
                                                </td>
                                                <td style={{ padding: '5px 6px', textAlign: 'right', fontWeight: 700, color: '#0f172a' }}>
                                                    {Number(item.jobAverageSlots || 0).toFixed(0)} Slots
                                                </td>
                                                <td style={{ padding: '5px 6px', textAlign: 'right', color: '#475569' }}>
                                                    {Number(item.bytesProcessedGb || 0).toFixed(1)} GB
                                                </td>
                                            </tr>
                                        ))
                                    ) : (
                                        <tr>
                                            <td colSpan={7} style={{ padding: '12px', textAlign: 'center', color: '#94a3b8' }}>
                                                조회 대상 연월에 기록된 장시간 소요 쿼리 내역이 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default BigQueryOptimizationPanel;
