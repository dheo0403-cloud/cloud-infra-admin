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
        highSlotQueries: [],
        lastUpdated: new Date().toLocaleTimeString('ko-KR')
    };

    const hasData = Boolean(
        data.currentMonthProcessedTb > 0 ||
        data.currentMonthJobCount > 0 ||
        (data.highCostQueries && data.highCostQueries.length > 0) ||
        (data.longDurationQueries && data.longDurationQueries.length > 0) ||
        (data.highSlotQueries && data.highSlotQueries.length > 0)
    );

    const maxTb = Math.max(...(data.dataProcessedTbTrend || [0]), 1.0);
    // Job Count는 단위가 달라 자체 최댓값 기준으로 높이 산정
    const maxJobs = Math.max(...(data.jobCountTrend || []).map(v => v || 0), 1);
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
                        {hasData ? (
                            <span style={{ fontSize: '10.5px', color: '#10b981', fontWeight: 600, backgroundColor: '#ecfdf5', padding: '2px 8px', borderRadius: '12px', border: '1px solid #a7f3d0' }}>
                                <i className="fas fa-check-circle mr-1"></i>INFORMATION_SCHEMA 분석 활성
                            </span>
                        ) : (
                            <span style={{ fontSize: '10.5px', color: '#64748b', fontWeight: 600, backgroundColor: '#f1f5f9', padding: '2px 8px', borderRadius: '12px', border: '1px solid #e2e8f0' }}>
                                <i className="fas fa-minus-circle mr-1"></i>분석 비활성 (데이터 없음)
                            </span>
                        )}
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

                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                            {/* Left: 4-Month Processed TB & Job Count Chart (Standardized IAM Structure & Height 170px) */}
                            <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '12px 14px' }}>
                                <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                                    <span>
                                        <i className="fas fa-chart-line mr-2" style={{ color: '#2563eb' }}></i>월간 데이터 사용량(TB) & Job Count 추이
                                    </span>
                                    <span style={{ fontSize: '10px', backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', color: '#64748b' }}>
                                        당월: <strong>{Number(data.currentMonthProcessedTb || 0).toFixed(2)} TB</strong> / <strong>{(data.currentMonthJobCount || 0).toLocaleString()} Jobs</strong>
                                    </span>
                                </div>

                                <div style={{ flexGrow: 1, display: 'flex', alignItems: 'flex-end', justifyContent: 'space-around', height: '170px', paddingTop: '24px', paddingBottom: '8px', borderBottom: '1px solid #e2e8f0', borderLeft: '1px solid #e2e8f0', marginLeft: '16px' }}>
                                    {displayDates.map((dateStr, idx) => {
                                        const tbVal = data.dataProcessedTbTrend?.[idx] || 0;
                                        const jcVal = data.jobCountTrend?.[idx] || 0;
                                        const heightPercent = tbVal > 0 ? Math.max(20, Math.min(85, Math.round((tbVal / maxTb) * 85))) : 0;
                                        const jcHeight = jcVal > 0 ? Math.max(20, Math.min(85, Math.round((jcVal / maxJobs) * 85))) : 0;

                                        return (
                                            <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '20%', height: '100%', justifyContent: 'flex-end' }}>
                                                <div style={{ display: 'flex', alignItems: 'flex-end', gap: '6px', width: '100%', justifyContent: 'center', height: '100%' }}>
                                                    <div className="bq-tb-bar" style={{
                                                        width: '16px',
                                                        height: `${heightPercent}%`,
                                                        backgroundColor: tbVal > 0 ? '#3b82f6' : 'transparent',
                                                        borderRadius: '3px 3px 0 0',
                                                        position: 'relative'
                                                    }}>
                                                        {tbVal > 0 && (
                                                            // 두 막대 라벨이 겹치지 않도록 TB 라벨은 막대 오른쪽 끝 기준 왼쪽으로 펼침
                                                            <span style={{ position: 'absolute', top: '-16px', right: 0, fontSize: '8px', fontWeight: 700, color: '#1e3a8a', whiteSpace: 'nowrap' }}>
                                                                {Number(tbVal).toFixed(2)}TB
                                                            </span>
                                                        )}
                                                    </div>
                                                    <div className="bq-job-bar" style={{
                                                        width: '16px',
                                                        height: `${jcHeight}%`,
                                                        backgroundColor: jcVal > 0 ? '#10b981' : 'transparent',
                                                        borderRadius: '3px 3px 0 0',
                                                        position: 'relative'
                                                    }}>
                                                        {jcVal > 0 && (
                                                            <span style={{ position: 'absolute', top: '-16px', left: 0, fontSize: '8px', fontWeight: 700, color: '#047857', whiteSpace: 'nowrap' }}>
                                                                {Number(jcVal).toLocaleString()}
                                                            </span>
                                                        )}
                                                    </div>
                                                </div>
                                                <span style={{ fontSize: '11px', marginTop: '8px', color: idx === 3 ? '#2563eb' : '#64748b', fontWeight: idx === 3 ? 700 : 400 }}>{dateStr}</span>
                                            </div>
                                        );
                                    })}
                                </div>

                                <div style={{ display: 'flex', justifyContent: 'center', gap: '16px', fontSize: '11px', marginTop: '6px' }}>
                                    <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                        <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#3b82f6', marginRight: '5px' }}></span>데이터 사용량 (TB)
                                    </span>
                                    <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                        <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#10b981', marginRight: '5px' }}></span>Job Count
                                    </span>
                                </div>
                                {/* 두 지표는 단위가 달라 각자의 최댓값 기준으로 높이를 그림 */}
                                <div className="bq-chart-scale-note" style={{ textAlign: 'center', fontSize: '9.5px', color: '#64748b', marginTop: '2px' }}>
                                    막대 높이: 지표별 4개월 최댓값 대비 (두 막대끼리의 높이 비교는 의미 없음, 수치는 라벨 참고)
                                </div>
                            </div>

                            {/* Right: 월별 신규 생성 테이블 용량 (쿼리 2: 해당 월에 생성된 테이블의 현재 용량) */}
                            {(() => {
                                const lgTrend = data.newTableLogicalGbTrend || [];
                                const pgTrend = data.newTablePhysicalGbTrend || [];
                                // 논리·물리 모두 GB 단위라 두 계열 공통 최댓값 기준으로 높이 산정 (막대끼리 비교 가능)
                                const maxGb = Math.max(...lgTrend.map(v => v || 0), ...pgTrend.map(v => v || 0), 0.01);
                                const barH = (v: number | null | undefined) => v && v > 0 ? Math.max(4, Math.min(85, Math.round((v / maxGb) * 85))) : 0;
                                const curLg = lgTrend[3];
                                return (
                                    <div className="report-card bq-new-table-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '12px 14px' }}>
                                        <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                                            <span>
                                                <i className="fas fa-database mr-2" style={{ color: '#2563eb' }}></i>월별 신규 생성 테이블 용량
                                            </span>
                                            <span style={{ fontSize: '10px', backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', color: '#64748b' }}>
                                                당월: <strong>{curLg == null ? '데이터 없음' : `${Number(curLg).toFixed(2)} GB`}</strong>
                                            </span>
                                        </div>

                                        <div style={{ flexGrow: 1, display: 'flex', alignItems: 'flex-end', justifyContent: 'space-around', height: '170px', paddingTop: '24px', paddingBottom: '8px', borderBottom: '1px solid #e2e8f0', borderLeft: '1px solid #e2e8f0', marginLeft: '16px' }}>
                                            {displayDates.map((dateStr, idx) => {
                                                const lg = lgTrend[idx];
                                                const pg = pgTrend[idx];
                                                // 0 GB(실제로 신규 용량 없음)와 null(조회 실패·미수집 → '-')을 구분해 표시
                                                const bars = [
                                                    { cls: 'bq-new-table-bar', v: lg, color: '#3b82f6', text: '#1e3a8a', side: { right: 0 } },
                                                    { cls: 'bq-new-table-phys-bar', v: pg, color: '#10b981', text: '#047857', side: { left: 0 } }
                                                ];
                                                return (
                                                    <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '20%', height: '100%', justifyContent: 'flex-end' }}>
                                                        <div style={{ display: 'flex', alignItems: 'flex-end', gap: '6px', width: '100%', justifyContent: 'center', height: '100%' }}>
                                                            {bars.map(b => (
                                                                <div key={b.cls} className={b.cls} style={{ width: '16px', height: `${barH(b.v)}%`, backgroundColor: barH(b.v) > 0 ? b.color : 'transparent', borderRadius: '3px 3px 0 0', position: 'relative' }}>
                                                                    {/* 두 막대 라벨이 겹치지 않도록 논리는 왼쪽, 물리는 오른쪽으로 펼침 */}
                                                                    <span style={{ position: 'absolute', top: '-16px', ...b.side, fontSize: '8px', fontWeight: 700, color: b.v == null ? '#94a3b8' : b.text, whiteSpace: 'nowrap' }}>
                                                                        {b.v == null ? '-' : `${Number(b.v).toFixed(2)}GB`}
                                                                    </span>
                                                                </div>
                                                            ))}
                                                        </div>
                                                        <span style={{ fontSize: '11px', marginTop: '8px', color: idx === 3 ? '#2563eb' : '#64748b', fontWeight: idx === 3 ? 700 : 400 }}>{dateStr}</span>
                                                    </div>
                                                );
                                            })}
                                        </div>

                                        <div style={{ display: 'flex', justifyContent: 'center', gap: '16px', fontSize: '11px', marginTop: '6px' }}>
                                            <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                                <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#3b82f6', marginRight: '5px' }}></span>논리 용량 (GB)
                                            </span>
                                            <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                                <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#10b981', marginRight: '5px' }}></span>물리 용량 (GB)
                                            </span>
                                        </div>
                                        <span style={{ fontSize: '9px', color: '#94a3b8', marginTop: '2px', display: 'block', textAlign: 'center' }}>
                                            * 해당 월에 생성된 테이블의 용량(수집 시점 기준, 삭제된 테이블 제외). '-'는 조회 권한 없음 또는 미수집
                                        </span>
                                    </div>
                                );
                            })()}
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
                                <i className="fas fa-tachometer-alt mr-1" style={{ color: '#059669' }}></i>3. 쿼리 성능 및 병목 현상 분석 (실행 시간 TOP 10 & 슬롯 사용량 TOP 10)
                            </span>
                        </div>

                        {/* 실행 시간 TOP 10 / 슬롯 사용량 TOP 10 을 같은 표 형식으로 각각 표시 */}
                        {[
                            { key: 'duration', title: '실행 시간 TOP 10', rows: data.longDurationQueries, empty: '조회 대상 연월에 기록된 장시간 소요 쿼리 내역이 없습니다.' },
                            // 적재된 TOP 10(총 슬롯 순)을 보고서 출력 시에만 평균 슬롯 내림차순으로 재정렬
                            { key: 'slot', title: '슬롯 사용량 TOP 10',
                              rows: [...(data.highSlotQueries || [])].sort((a, b) => (b.jobAverageSlots || 0) - (a.jobAverageSlots || 0)), empty: '조회 대상 연월에 기록된 슬롯 사용량 상위 쿼리 내역이 없습니다.' }
                        ].map(section => (
                        <div key={section.key} className="bq-perf-table" style={{ backgroundColor: '#ffffff', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '8px', overflowX: 'auto', marginBottom: section.key === 'duration' ? '8px' : 0 }}>
                            <div style={{ fontSize: '10.5px', fontWeight: 700, color: '#334155', marginBottom: '4px' }}>{section.title}</div>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '10px', textAlign: 'left' }}>
                                <thead>
                                    <tr style={{ backgroundColor: '#f8fafc', borderBottom: '1px solid #e2e8f0', color: '#64748b' }}>
                                        <th style={{ padding: '5px 6px', width: '32px', textAlign: 'center', fontWeight: 700 }}>순위</th>
                                        <th style={{ padding: '5px 6px', width: '65px', fontWeight: 700 }}>실행 일자</th>
                                        <th style={{ padding: '5px 6px', width: '130px', fontWeight: 700 }}>실행 계정 (IAM)</th>
                                        <th style={{ padding: '5px 6px', fontWeight: 700 }}>SQL 쿼리문</th>
                                        {section.key === 'duration' && <th style={{ padding: '5px 6px', width: '75px', textAlign: 'right', fontWeight: 700 }}>실행 소요시간</th>}
                                        <th style={{ padding: '5px 6px', width: '65px', textAlign: 'right', fontWeight: 700 }}>평균 슬롯</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {section.rows && section.rows.length > 0 ? (
                                        section.rows.map((item, idx) => (
                                            <tr key={idx} style={{ borderBottom: '1px solid #f1f5f9' }}>
                                                <td style={{ padding: '5px 6px', textAlign: 'center', fontWeight: 800, color: idx < 3 ? '#ea580c' : '#64748b' }}>
                                                    {section.key === 'slot' ? idx + 1 : item.rank}
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
                                                {section.key === 'duration' && (
                                                    <td style={{ padding: '5px 6px', textAlign: 'right', fontWeight: 700, color: '#ea580c' }}>
                                                        {Number(item.executionTimeSeconds || 0).toFixed(1)}초
                                                    </td>
                                                )}
                                                <td style={{ padding: '5px 6px', textAlign: 'right', fontWeight: 700, color: '#0f172a' }}>
                                                    {Number(item.jobAverageSlots || 0).toFixed(0)} Slots
                                                </td>
                                            </tr>
                                        ))
                                    ) : (
                                        <tr>
                                            <td colSpan={section.key === 'duration' ? 6 : 5} style={{ padding: '12px', textAlign: 'center', color: '#94a3b8' }}>
                                                {section.empty}
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
};

export default BigQueryOptimizationPanel;
