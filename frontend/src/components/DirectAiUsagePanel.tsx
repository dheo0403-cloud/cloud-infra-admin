import React, { useEffect, useState } from 'react';
import { getDirectAiMetrics, DirectAiMetricsDto, AiErrorRateSeriesDto } from '../services/api';

interface DirectAiUsagePanelProps {
    projectId?: string;
    targetYearMonth?: string;
    isEditMode?: boolean;
    onHideSection?: () => void;
}

// 토큰/건수 축약 표기 (1.2M, 3.4K)
const fmt = (n: number) => n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}K` : `${n}`;

const MAX_RATIO_ROWS = 5;
const ERROR_LINE_COLORS = ['#ef4444', '#f59e0b', '#3b82f6'];

const cardTitleIcon = (icon: string, color: string) => <i className={`fas ${icon} mr-1`} style={{ color }}></i>;

/**
 * 기준월 일별 에러율 꺾은선 (SVG). 값이 없는 날(null)은 선을 끊는다
 */
const ErrorRateChart: React.FC<{ dates: string[]; series: AiErrorRateSeriesDto[] }> = ({ dates, series }) => {
    // 가로로 넓은 viewBox: 카드 폭(약 580px)에서 높이 170px 안팎이 되도록
    const W = 520, H = 150, PAD_L = 30, PAD_R = 8, PAD_T = 8, PAD_B = 18;
    const values = series.flatMap(s => s.dailyErrorRates.filter((v): v is number => v !== null));
    const yMax = Math.max(1, Math.ceil(Math.max(0, ...values)));
    const x = (i: number) => PAD_L + (dates.length <= 1 ? 0 : (i / (dates.length - 1)) * (W - PAD_L - PAD_R));
    const y = (v: number) => PAD_T + (1 - v / yMax) * (H - PAD_T - PAD_B);
    const xLabelIdx = dates.length > 2 ? [0, Math.floor((dates.length - 1) / 2), dates.length - 1] : dates.map((_, i) => i);

    // null로 끊긴 구간별 점 목록
    const segments = (rates: (number | null)[]) => {
        const segs: string[] = [];
        let cur: string[] = [];
        rates.forEach((v, i) => {
            if (v === null) {
                if (cur.length) segs.push(cur.join(' '));
                cur = [];
            } else {
                cur.push(`${x(i).toFixed(1)},${y(v).toFixed(1)}`);
            }
        });
        if (cur.length) segs.push(cur.join(' '));
        return segs;
    };

    return (
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="AI 모델 호출 일별 에러율">
            {[0, yMax / 2, yMax].map(v => (
                <g key={v}>
                    <line x1={PAD_L} x2={W - PAD_R} y1={y(v)} y2={y(v)} stroke="#e2e8f0" strokeWidth={1} />
                    <text x={PAD_L - 4} y={y(v) + 3} textAnchor="end" fontSize="9" fill="#94a3b8">{`${Number(v.toFixed(1))}%`}</text>
                </g>
            ))}
            {xLabelIdx.map(i => (
                <text key={i} x={x(i)} y={H - 4} textAnchor={i === 0 ? 'start' : i === dates.length - 1 ? 'end' : 'middle'} fontSize="9" fill="#94a3b8">{dates[i]}</text>
            ))}
            {series.map((s, si) => segments(s.dailyErrorRates).map((pts, k) => (
                pts.includes(' ')
                    ? <polyline key={`${si}-${k}`} points={pts} fill="none" stroke={ERROR_LINE_COLORS[si]} strokeWidth={1.5} strokeLinejoin="round" />
                    : <circle key={`${si}-${k}`} cx={pts.split(',')[0]} cy={pts.split(',')[1]} r={1.8} fill={ERROR_LINE_COLORS[si]} />
            )))}
        </svg>
    );
};

const DirectAiUsagePanel: React.FC<DirectAiUsagePanelProps> = ({
    projectId,
    targetYearMonth,
    isEditMode = false,
    onHideSection
}) => {
    const [metrics, setMetrics] = useState<DirectAiMetricsDto | null>(null);

    useEffect(() => {
        let ignore = false;
        getDirectAiMetrics(projectId, targetYearMonth)
            .then(res => { if (!ignore && res.data) setMetrics(res.data); })
            .catch(err => console.error('Failed to fetch Direct AI usage metrics:', err));
        return () => { ignore = true; };
    }, [projectId, targetYearMonth]);

    const dates = metrics?.dates || [];
    const inputTrend = metrics?.inputTokensTrend || [];
    const outputTrend = metrics?.outputTokensTrend || [];
    const models = metrics?.models || [];
    const totalTokens = Number(metrics?.totalTokens || 0);
    const totalInvocations = Number(metrics?.totalInvocations || 0);
    const errorDates = metrics?.errorRateDates || [];
    const errorSeries = metrics?.errorRateSeries || [];
    const hasErrorData = errorSeries.some(s => s.dailyErrorRates.some(v => v !== null));
    const deniedDays = Number(metrics?.permissionDeniedDays || 0);

    const maxTokenValue = Math.max(...inputTrend, ...outputTrend, 1);
    const isDirectAiEmpty = dates.length === 0 || (totalTokens === 0 && totalInvocations === 0);
    // 권한 없음이면 미사용과 구분해 PDF에서도 숨기지 않는다
    const hidePrint = isDirectAiEmpty && deniedDays === 0;
    const otherModels = models.slice(MAX_RATIO_ROWS);
    const otherInvocations = otherModels.reduce((sum, m) => sum + Number(m.invocations || 0), 0);
    const ratio = (inv: number) => totalInvocations > 0 ? (inv / totalInvocations) * 100 : 0;

    const subCard: React.CSSProperties = { marginBottom: 0, display: 'flex', flexDirection: 'column' };
    const emptyText: React.CSSProperties = { fontSize: '10px', color: '#94a3b8', padding: '6px 0' };

    return (
        <div className={`direct-ai-section ${hidePrint ? "print-hide-empty" : ""}`} style={{ marginBottom: 0 }}>
            <div className="report-card" style={{ marginBottom: 0, borderTop: '4px solid #2563eb' }}>
                {/* Header */}
                <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ display: 'flex', alignItems: 'center' }}>
                        <i className="fas fa-brain mr-2" style={{ color: '#2563eb' }}></i>
                        Direct AI Usage (AI 서비스 직접 사용)
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
                        {!isDirectAiEmpty ? (
                            <span style={{ fontSize: '10px', fontWeight: 700, color: '#10b981', backgroundColor: '#ecfdf5', padding: '2px 8px', borderRadius: '12px', border: '1px solid #a7f3d0' }}>
                                <i className="fas fa-check-circle mr-1"></i>Direct AI 사용 중
                            </span>
                        ) : deniedDays > 0 ? (
                            <span className="ai-permission-badge" style={{ fontSize: '10px', fontWeight: 700, color: '#b45309', backgroundColor: '#fffbeb', padding: '2px 8px', borderRadius: '12px', border: '1px solid #fde68a' }}>
                                <i className="fas fa-lock mr-1"></i>권한 없음
                            </span>
                        ) : (
                            <span style={{ fontSize: '10px', fontWeight: 600, color: '#64748b', backgroundColor: '#f1f5f9', padding: '2px 8px', borderRadius: '4px', border: '1px solid #e2e8f0' }}>
                                Direct AI 미사용
                            </span>
                        )}
                    </div>
                </div>

                {deniedDays > 0 && (
                    <div className="ai-permission-notice" style={{ fontSize: '10px', color: '#b45309', backgroundColor: '#fffbeb', border: '1px solid #fde68a', borderRadius: '6px', padding: '6px 10px', marginBottom: '12px' }}>
                        <i className="fas fa-lock mr-1"></i>
                        기준월 중 {deniedDays}일은 AI 사용량 조회 권한이 없어 수집하지 못했습니다. 아래 수치에서 해당 일은 빠져 있습니다.
                    </div>
                )}

                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                    {/* 왼쪽: 월간 누적 토큰 + 월별 토큰 사용량 트렌드 */}
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                        <div className="ai-total-tokens" style={{ backgroundColor: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: '8px', padding: '10px 12px' }}>
                            <span style={{ display: 'block', fontSize: '10px', color: '#64748b', fontWeight: 600 }}>월간 누적 토큰</span>
                            <strong style={{ fontSize: '14px', fontWeight: 800, color: '#0f172a', fontFamily: 'Pretendard, sans-serif' }}>
                                {fmt(totalTokens)} <span style={{ fontSize: '9px', fontWeight: 500, color: '#94a3b8' }}>Tokens</span>
                            </strong>
                        </div>

                        <div className="report-card ai-token-trend" style={{ ...subCard, flexGrow: 1 }}>
                            <div className="report-card-title">
                                <span>{cardTitleIcon('fa-chart-bar', '#2563eb')}월별 토큰 사용량 트렌드</span>
                                <span style={{ fontSize: '10px', backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', color: '#64748b' }}>최근 4개월</span>
                            </div>

                            <div style={{ flexGrow: 1, display: 'flex', alignItems: 'flex-end', justifyContent: 'space-around', height: '170px', paddingTop: '24px', paddingBottom: '8px', borderBottom: '1px solid #e2e8f0', borderLeft: '1px solid #e2e8f0', marginLeft: '16px' }}>
                                {dates.map((date, idx) => {
                                    const inTok = Number(inputTrend[idx] || 0);
                                    const outTok = Number(outputTrend[idx] || 0);
                                    const inHeight = inTok > 0 ? (inTok / maxTokenValue) * 85 : 0;
                                    const outHeight = outTok > 0 ? (outTok / maxTokenValue) * 85 : 0;
                                    return (
                                        <div key={date} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '20%', height: '100%', justifyContent: 'flex-end' }}>
                                            <div style={{ display: 'flex', alignItems: 'flex-end', gap: '14px', width: '100%', justifyContent: 'center', height: '100%' }}>
                                                {[{ v: inTok, h: inHeight, c: '#3b82f6', t: '#1e3a8a' }, { v: outTok, h: outHeight, c: '#10b981', t: '#047857' }].map((b, bi) => (
                                                    <div key={bi} style={{ width: '16px', height: `${b.h}%`, minHeight: b.h > 0 ? '2px' : 0, backgroundColor: b.h > 0 ? b.c : 'transparent', borderRadius: '3px 3px 0 0', position: 'relative' }}>
                                                        {b.v > 0 && (
                                                            <span style={{ position: 'absolute', top: '-16px', left: '50%', transform: 'translateX(-50%)', fontSize: '8px', fontWeight: 700, color: b.t, whiteSpace: 'nowrap' }}>
                                                                {fmt(b.v)}
                                                            </span>
                                                        )}
                                                    </div>
                                                ))}
                                            </div>
                                            <span style={{ fontSize: '11px', marginTop: '8px', color: idx === dates.length - 1 ? '#2563eb' : '#64748b', fontWeight: idx === dates.length - 1 ? 700 : 400 }}>{date}</span>
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
                    </div>

                    {/* 오른쪽: AI 모델별 호출 비중 + AI 모델 호출 에러율 */}
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                        <div className="report-card ai-model-ratio" style={subCard}>
                            <div className="report-card-title">
                                <span>{cardTitleIcon('fa-layer-group', '#8b5cf6')}AI 모델별 호출 비중</span>
                                <span style={{ fontSize: '10px', color: '#64748b' }}>총 {totalInvocations.toLocaleString()}회</span>
                            </div>
                            {models.length > 0 ? (
                                <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                                    {[...models.slice(0, MAX_RATIO_ROWS).map(m => ({ key: `${m.publisher}/${m.model}`, label: m.model || '(알 수 없음)', sub: m.publisher, inv: Number(m.invocations || 0) })),
                                      ...(otherModels.length > 0 ? [{ key: 'other', label: `기타 ${otherModels.length}개 모델`, sub: '', inv: otherInvocations }] : [])
                                    ].map(r => {
                                        const pct = ratio(r.inv);
                                        return (
                                            <div key={r.key} style={{ fontSize: '10px' }}>
                                                <div style={{ display: 'flex', justifyContent: 'space-between', gap: '8px', marginBottom: '2px' }}>
                                                    <span style={{ color: '#334155', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', minWidth: 0 }} title={`${r.label} ${r.sub}`}>
                                                        {r.label} <span style={{ color: '#94a3b8', fontWeight: 400 }}>{r.sub}</span>
                                                    </span>
                                                    <span style={{ color: '#7c3aed', fontWeight: 700, flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}>{pct.toFixed(1)}%</span>
                                                </div>
                                                <div style={{ height: '6px', backgroundColor: '#f1f5f9', borderRadius: '3px', overflow: 'hidden' }}>
                                                    <div style={{ width: `${pct}%`, height: '100%', backgroundColor: '#8b5cf6' }}></div>
                                                </div>
                                            </div>
                                        );
                                    })}
                                </div>
                            ) : (
                                <div style={emptyText}>기준월 모델 호출 기록이 없습니다.</div>
                            )}
                        </div>

                        <div className="report-card ai-error-rate" style={{ ...subCard, flexGrow: 1 }}>
                            <div className="report-card-title">
                                <span>{cardTitleIcon('fa-exclamation-triangle', '#ef4444')}AI 모델 호출 에러율</span>
                                <span style={{ fontSize: '10px', backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', color: '#64748b' }}>기준월 일별 · 2xx 외 응답</span>
                            </div>
                            {hasErrorData ? (
                                <>
                                    <ErrorRateChart dates={errorDates} series={errorSeries} />
                                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 12px', fontSize: '10px', marginTop: '4px' }}>
                                        {errorSeries.map((s, si) => (
                                            <span key={`${s.publisher}/${s.model}`} style={{ display: 'flex', alignItems: 'center', color: '#334155', fontWeight: 600, minWidth: 0 }}>
                                                <span style={{ display: 'inline-block', width: '10px', height: '3px', backgroundColor: ERROR_LINE_COLORS[si], marginRight: '4px', flexShrink: 0 }}></span>
                                                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.model}</span>
                                                <span style={{ color: '#ef4444', marginLeft: '4px', fontVariantNumeric: 'tabular-nums' }}>
                                                    {s.monthlyErrorRate !== null && s.monthlyErrorRate !== undefined ? `${s.monthlyErrorRate.toFixed(2)}%` : '—'}
                                                </span>
                                            </span>
                                        ))}
                                    </div>
                                </>
                            ) : (
                                <div style={emptyText}>기준월 에러율 수집 기록이 없습니다.</div>
                            )}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default DirectAiUsagePanel;
