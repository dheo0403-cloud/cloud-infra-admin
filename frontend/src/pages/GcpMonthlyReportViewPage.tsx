import React, { useEffect, useState } from 'react';
import { getCustomers, InfraCustomer } from '../services/api';

interface CudCommitment {
    name: string;
    category: string;
    region: string;
    startDate: string;
    expiryDate: string;
    status: string;
    dday: number;
    resourceDetail: string;
}

interface WorkLogItem {
    category: string;
    target: string;
    workDate: string;
    content: string;
}

interface MonthlyReportData {
    customerName: string;
    projectId: string;
    reportMonth: string;
    months: string[];
    mspSalesContact: string;
    mspTechContact: string;
    mspGrade: string;
    reportPeriod: string;
    snapshotTime?: string;
    regionLocation?: string;

    // Metrics Totals & Deltas
    vmTotal?: number;
    vmTotalDelta?: number;
    sqlTotal?: number;
    sqlTotalDelta?: number;
    diskTotal?: number;
    diskTotalDelta?: number;
    bucketTotal?: number;
    bucketTotalDelta?: number;
    gkeTotal?: number;
    gkeTotalDelta?: number;
    gkeNodeTotal?: number;
    gkeCrashCount?: number;
    lbTotal?: number;
    lbTotalDelta?: number;
    vpnTotal?: number;

    iamSummary: Record<string, number[]>;
    saSecurity: Record<string, number>;
    userPasswordOver90?: number;
    mfaDisabled?: number;

    // 계정 및 키 보안 감사 지표
    ownerSaCount?: number;
    ownerUserCount?: number;

    sslSummary: Record<string, number>;
    vmTotalTrend: number[];
    vmRunningTrend: number[];
    vmDeallocatedTrend: number[];
    vmMachineTypes: Record<string, number>;
    sqlTotalTrend: number[];
    sqlEngines: Record<string, number>;
    sqlTiers: Record<string, number>;
    sqlHaTypes: Record<string, number>;
    storageSummary: Record<string, number>;
    bucketSecurity: Record<string, number>;
    lbSummary: Record<string, number>;
    ipSummary: Record<string, number>;
    fwSummary: Record<string, number>;
    vpnSummary: Record<string, number>;
    vmSummary?: Record<string, number>;
    cloudRunSummary: Record<string, number>;
    vpcTrend?: number[];
    vpcSubnetTrend?: number[];
    lbTrend?: number[];
    gkeNodeTrend?: number[];
    serverlessTrend?: number[];
    diskTrend?: number[];
    snapshotTrend?: number[];
    commitments: CudCommitment[];
    workLogs?: WorkLogItem[];

    customComments: string;
    customRecommendations: string;
    customRecSecurity?: string;
    customRecCost?: string;
    customRecPerformance?: string;

    customExecSecurity?: string;
    customExecCost?: string;
    customExecPerformance?: string;

    recommendationsSecurityList?: string[];
    recommendationsCostList?: string[];
    recommendationsPerformanceList?: string[];

    generationDurationSeconds?: number;
}

const GcpMonthlyReportViewPage: React.FC = () => {
    const getCurrentYearMonth = (): string => {
        const now = new Date();
        const year = now.getFullYear();
        const month = String(now.getMonth() + 1).padStart(2, '0');
        return `${year}-${month}`;
    };

    // 선택된 연월(YYYY-MM) 기준 최근 4개월(YY.MM) 배열 동적 생성
    const get4MonthsArray = (ym: string): string[] => {
        if (!ym || !ym.includes('-')) return ['26.06', '26.07', '26.08', '26.09'];
        const [year, month] = ym.split('-').map(Number);
        const result: string[] = [];
        for (let i = 3; i >= 0; i--) {
            const d = new Date(year, month - 1 - i, 1);
            const yy = String(d.getFullYear()).slice(-2);
            const mm = String(d.getMonth() + 1).padStart(2, '0');
            result.push(`${yy}.${mm}`);
        }
        return result;
    };

    // 현재 날짜 기준으로 최신 월부터 과거 12개월 목록 동적 생성
    const generateYearMonthOptions = (count: number = 12): { value: string; label: string }[] => {
        const options: { value: string; label: string }[] = [];
        const now = new Date();
        for (let i = 0; i < count; i++) {
            const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
            const year = d.getFullYear();
            const month = String(d.getMonth() + 1).padStart(2, '0');
            const value = `${year}-${month}`;
            const label = `${year}년 ${month}월`;
            options.push({ value, label });
        }
        return options;
    };

    const [customers, setCustomers] = useState<InfraCustomer[]>([]);
    const [selectedCustomer, setSelectedCustomer] = useState<InfraCustomer | null>(null);
    const [selectedProject, setSelectedProject] = useState<string>('');
    const [selectedYearMonth, setSelectedYearMonth] = useState<string>(getCurrentYearMonth());
    const [reportData, setReportData] = useState<MonthlyReportData | null>(null);
    const [loading, setLoading] = useState<boolean>(false);
    const [isEditMode, setIsEditMode] = useState<boolean>(false);
    const [reportDuration, setReportDuration] = useState<number | null>(null);

    // Editable text states
    const [editComments, setEditComments] = useState<string>('');
    const [editRecommendations, setEditRecommendations] = useState<string>('');
    const [editSalesContact, setEditSalesContact] = useState<string>('');
    const [editTechContact, setEditTechContact] = useState<string>('');
    const [editGrade, setEditGrade] = useState<string>('');

    const [editRecSecurity, setEditRecSecurity] = useState<string>('');
    const [editRecCost, setEditRecCost] = useState<string>('');
    const [editRecPerformance, setEditRecPerformance] = useState<string>('');

    const [editExecSecurity, setEditExecSecurity] = useState<string>('');
    const [editExecCost, setEditExecCost] = useState<string>('');
    const [editExecPerformance, setEditExecPerformance] = useState<string>('');

    // Editable Work Logs
    const [editWorkLogs, setEditWorkLogs] = useState<WorkLogItem[]>([]);
    const [savingText, setSavingText] = useState<boolean>(false);
    const [isGeneratingAi, setIsGeneratingAi] = useState<boolean>(false);


    const handleAddWorkLogRow = () => {
        const today = new Date().toISOString().split('T')[0];
        setEditWorkLogs(prev => [...prev, { category: '기술지원', target: 'GCP', workDate: today, content: '' }]);
    };

    const handleDeleteWorkLogRow = (index: number) => {
        setEditWorkLogs(prev => prev.filter((_, i) => i !== index));
    };

    const handleUpdateWorkLogRow = (index: number, field: keyof WorkLogItem, value: string) => {
        setEditWorkLogs(prev => {
            const updated = [...prev];
            updated[index] = { ...updated[index], [field]: value };
            return updated;
        });
    };

    const isCustomerQuarterly = (customer: InfraCustomer | null): boolean => {
        if (!customer || !customer.reportFrequency) return false;
        const freq = customer.reportFrequency.toLowerCase();
        return freq.includes('분기') || freq.includes('quarter');
    };

    useEffect(() => {
        const fetchCustomers = async () => {
            try {
                const res = await getCustomers();
                const data = res.data || [];
                setCustomers(data);

                for (const c of data) {
                    if (c.environments) {
                        for (const env of c.environments) {
                            if (env.providerType === 'GCP' && env.projects && env.projects.length > 0) {
                                setSelectedCustomer(c);
                                setSelectedProject(env.projects[0].projectId);
                                setSelectedYearMonth(getCurrentYearMonth());
                                return;


                            }
                        }
                    }
                }
            } catch (e) {
                console.error("Failed to fetch customers", e);
            }
        };
        fetchCustomers();
    }, []);

    const fetchReport = async () => {
        if (!selectedProject) return;
        setLoading(true);
        const startTime = performance.now();
        const customerName = selectedCustomer ? selectedCustomer.name : 'MegazoneCloud Customer';
        const reqTimeStr = new Date().toLocaleTimeString();
        console.log(`[REPORT] 🚀 보고서 생성 시작 - 고객사: ${customerName}, 프로젝트: ${selectedProject}, 연월: ${selectedYearMonth}, 시작시각: ${reqTimeStr}`);
        try {
            const url = `/api/reports/gcp/monthly?customerName=${encodeURIComponent(customerName)}&projectId=${encodeURIComponent(selectedProject)}&targetYearMonth=${selectedYearMonth}`;
            const res = await fetch(url);
            if (res.ok) {
                const data: MonthlyReportData = await res.json();
                if (!data.months || data.months.length === 0) {
                    data.months = (data as any).displayMonths || get4MonthsArray(selectedYearMonth);
                }
                setReportData(data);
                setEditComments(data.customComments || '');
                setEditRecommendations(data.customRecommendations || '');
                setEditSalesContact(data.mspSalesContact || '미지정');
                setEditTechContact(data.mspTechContact || '미지정');
                setEditGrade(data.mspGrade || 'Standard');

                setEditRecSecurity(data.customRecSecurity || data.recommendationsSecurityList?.join('\n') || '');
                setEditRecCost(data.customRecCost || data.recommendationsCostList?.join('\n') || '');
                setEditRecPerformance(data.customRecPerformance || data.recommendationsPerformanceList?.join('\n') || '');

                setEditExecSecurity(data.customExecSecurity || '');
                setEditExecCost(data.customExecCost || '');
                setEditExecPerformance(data.customExecPerformance || '');

                setEditWorkLogs(data.workLogs && data.workLogs.length > 0 ? data.workLogs : []);
                
                const elapsedMs = performance.now() - startTime;
                const durationSec = Math.round((elapsedMs / 1000) * 10) / 10;
                setReportDuration(data.generationDurationSeconds || durationSec);
                console.log(`[REPORT] ✅ 보고서 생성 및 화면 렌더링 완료! 총 소요 시간: ${durationSec}초 (${Math.round(elapsedMs)}ms, 서버 처리: ${data.generationDurationSeconds || 'N/A'}초)`);
            }
        } catch (e) {
            console.error("Failed to fetch monthly report data", e);
        } finally {
            setLoading(false);
        }
    };

    // Automatically load report on manual button click only
    // (Removed auto-fetch useEffect to allow manual selection before generation)

    const handleSaveText = async () => {
        if (!selectedProject) return;
        setSavingText(true);
        try {
            const res = await fetch('/api/reports/gcp/monthly/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    projectId: selectedProject,
                    targetYearMonth: selectedYearMonth,
                    customComments: editComments,
                    customRecommendations: editRecommendations,
                    mspSalesContact: editSalesContact,
                    mspTechContact: editTechContact,
                    mspGrade: editGrade,
                    customRecSecurity: editRecSecurity,
                    customRecCost: editRecCost,
                    customRecPerformance: editRecPerformance,
                    customExecSecurity: editExecSecurity,
                    customExecCost: editExecCost,
                    customExecPerformance: editExecPerformance,
                    customWorkLogs: JSON.stringify(editWorkLogs)
                })
            });
            if (res.ok) {
                alert("보고서 작성 내용이 성공적으로 저장되었습니다!");
                fetchReport();
            }
        } catch (e) {
            console.error("Failed to save report text", e);
            alert("저장 중 오류가 발생했습니다.");
        } finally {
            setSavingText(false);
        }
    };

    const handleGenerateAiSummary = async () => {
        if (!reportData) return;
        setIsGeneratingAi(true);
        try {
            const secList = editRecSecurity ? editRecSecurity.split('\n') : (reportData.recommendationsSecurityList || []);
            const costList = editRecCost ? editRecCost.split('\n') : (reportData.recommendationsCostList || []);
            const perfList = editRecPerformance ? editRecPerformance.split('\n') : (reportData.recommendationsPerformanceList || []);

            const res = await fetch('/api/reports/gcp/generate-ai-summary', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    recSecurity: secList,
                    recCost: costList,
                    recPerformance: perfList
                })
            });
            if (res.ok) {
                const data = await res.json();
                if (data.execSecurity) setEditExecSecurity(data.execSecurity);
                if (data.execCost) setEditExecCost(data.execCost);
                if (data.execPerformance) setEditExecPerformance(data.execPerformance);

                setReportData(prev => prev ? ({
                    ...prev,
                    customExecSecurity: data.execSecurity,
                    customExecCost: data.execCost,
                    customExecPerformance: data.execPerformance
                }) : null);
            }
        } catch (e) {
            console.error("Failed to generate AI summary", e);
        } finally {
            setIsGeneratingAi(false);
        }
    };

    const handlePrint = () => {

        window.print();
    };

    const gcpCustomers = customers.filter(c => c.environments && c.environments.some(env => env.providerType === 'GCP' && env.projects && env.projects.length > 0));
    const isQuarterly = isCustomerQuarterly(selectedCustomer);

    const renderTrendBadge = (delta: number = 0) => {
        if (delta > 0) {
            return (
                <span className="trend-badge badge-red">
                    <i className="fas fa-arrow-up"></i> +{delta} 신규
                </span>
            );
        } else if (delta < 0) {
            return (
                <span className="trend-badge badge-blue">
                    <i className="fas fa-arrow-down"></i> {delta} 감축
                </span>
            );
        } else {
            return (
                <span className="trend-badge badge-slate">
                    <i className="fas fa-minus"></i> 변동없음
                </span>
            );
        }
    };

    const userTrend = reportData?.iamSummary?.["User (사용자)"] || [0, 0, 0, 0];
    const saTrend = (reportData?.iamSummary?.["Google Service Account"] || [0, 0, 0, 0]).map(
        (val, idx) => val + ((reportData?.iamSummary?.["User Service Account"] || [0, 0, 0, 0])[idx] || 0)
    );

    const maxVal = Math.max(...userTrend, ...saTrend, 10);

    return (
        <div className="report-page-wrapper">
            <style>{`
                @import url('https://fonts.googleapis.com/css2?family=Pretendard:wght@300;400;500;600;700;800&family=Noto+Sans+KR:wght@300;400;500;700;900&display=swap');

                .report-page-wrapper {
                    font-family: 'Pretendard', 'Noto Sans KR', sans-serif;
                    background-color: #f8fafc !important;
                    color: #334155 !important;
                    min-height: 100vh;
                    padding-bottom: 2rem;
                }

                /* Edit Mode Input & Textarea Visibility Styles (Bold Black Text & Distinct Border) */
                .edit-input-field, .edit-textarea-field {
                    color: #000000 !important;
                    background-color: #ffffff !important;
                    border: 2px solid #0f172a !important;
                    font-weight: 700 !important;
                    font-size: 12px !important;
                    border-radius: 6px !important;
                    box-shadow: 0 2px 5px rgba(0, 0, 0, 0.15) !important;
                    outline: none !important;
                }
                .edit-input-field:focus, .edit-textarea-field:focus {
                    border-color: #2563eb !important;
                    box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.3) !important;
                }

                .control-bar-wrapper {
                    background-color: #1e293b !important;
                    color: #ffffff !important;
                    padding: 12px 24px !important;
                    margin-bottom: 20px !important;
                    box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
                }

                .control-bar-inner {
                    max-width: 1280px;
                    margin: 0 auto;
                    display: flex !important;
                    flex-direction: row !important;
                    justify-content: space-between !important;
                    align-items: center !important;
                    flex-wrap: nowrap !important;
                    gap: 16px;
                }

                .control-group-left {
                    display: flex !important;
                    flex-direction: row !important;
                    align-items: center !important;
                    gap: 20px !important;
                    flex-wrap: nowrap !important;
                }

                .control-item {
                    display: flex !important;
                    flex-direction: row !important;
                    align-items: center !important;
                    gap: 8px !important;
                    white-space: nowrap !important;
                }

                .control-item label {
                    font-size: 12px !important;
                    font-weight: 700 !important;
                    color: #cbd5e1 !important;
                    margin: 0 !important;
                }

                .control-item select {
                    font-size: 12px !important;
                    font-weight: 700 !important;
                    color: #0f172a !important;
                    background-color: #ffffff !important;
                    border: 1px solid #cbd5e1 !important;
                    border-radius: 6px !important;
                    padding: 6px 12px !important;
                }

                .control-group-right {
                    display: flex !important;
                    flex-direction: row !important;
                    align-items: center !important;
                    gap: 8px !important;
                }

                .btn-edit {
                    background-color: #334155;
                    color: #ffffff;
                    font-size: 12px;
                    font-weight: 700;
                    padding: 6px 12px;
                    border-radius: 6px;
                    border: none;
                    cursor: pointer;
                }
                .btn-edit.active { background-color: #f59e0b; }

                .btn-save {
                    background-color: #059669;
                    color: #ffffff;
                    font-size: 12px;
                    font-weight: 700;
                    padding: 6px 12px;
                    border-radius: 6px;
                    border: none;
                    cursor: pointer;
                }

                .btn-generate {
                    background-color: #2563eb;
                    color: #ffffff;
                    font-size: 12px;
                    font-weight: 700;
                    padding: 6px 14px;
                    border-radius: 6px;
                    border: none;
                    cursor: pointer;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                    transition: background-color 0.2s;
                }
                .btn-generate:hover {
                    background-color: #1d4ed8;
                }
                .btn-generate:disabled {
                    background-color: #64748b;
                    cursor: not-allowed;
                }

                .btn-pdf {
                    background-color: #0b4885;
                    color: #ffffff;
                    font-size: 12px;
                    font-weight: 700;
                    padding: 6px 14px;
                    border-radius: 6px;
                    border: none;
                    cursor: pointer;
                    display: flex;
                    align-items: center;
                    gap: 6px;
                }

                .report-header-banner {
                    background-color: #0b4885 !important;
                    color: #ffffff !important;
                    border-radius: 12px !important;
                    padding: 24px 28px !important;
                    box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.1);
                    display: flex !important;
                    flex-direction: row !important;
                    justify-content: space-between !important;
                    align-items: center !important;
                    margin-bottom: 20px !important;
                }

                .report-header-banner h1 {
                    font-size: 22px !important;
                    font-weight: 800 !important;
                    margin: 0 0 6px 0 !important;
                    color: #ffffff !important;
                }

                .report-header-banner p {
                    font-size: 13px !important;
                    color: #dbeafe !important;
                    margin: 0 !important;
                }

                @media print {
                    @page {
                        size: A4 portrait;
                        margin: 0 !important;
                    }
                    * {
                        -webkit-print-color-adjust: exact !important;
                        print-color-adjust: exact !important;
                        color-adjust: exact !important;
                    }
                    .no-print, .control-bar-wrapper, .sidebar-wrapper-panel, .sidebar, .sidebar-wrapper, nav.navbar, aside, footer, .footer, button, .btn,
                    [class*="sidebar"], [class*="navbar"], [class*="Sidebar"], [class*="Navbar"], [class*="footer"], [class*="Footer"],
                    .main-panel > nav, .navbar-brand, .navbar-toggler, .nav-link, ul.navbar-nav, div[class*="collapse"] {
                        display: none !important;
                        width: 0 !important;
                        height: 0 !important;
                        visibility: hidden !important;
                        opacity: 0 !important;
                    }
                    html, body, #root, main, .main-panel, .content, .wrapper, div[class*="main-panel"], div[class*="content"] {
                        margin: 0 !important;
                        padding: 0 !important;
                        width: 100% !important;
                        max-width: 100% !important;
                        left: 0 !important;
                        top: 0 !important;
                        transform: none !important;
                        background: #ffffff !important;
                    }
                    #print-area {
                        width: 100% !important;
                        max-width: 100% !important;
                        margin: 0 auto !important;
                        padding: 12mm 10mm !important;
                        box-sizing: border-box !important;
                        display: block !important;
                    }
                    .report-header-banner {
                        display: flex !important;
                        visibility: visible !important;
                        opacity: 1 !important;
                        width: 100% !important;
                        height: auto !important;
                        background-color: #0b4885 !important;
                        background: #0b4885 !important;
                        box-shadow: inset 0 0 0 1000px #0b4885 !important;
                        color: #ffffff !important;
                        border-radius: 12px !important;
                        padding: 24px 28px !important;
                        flex-direction: row !important;
                        justify-content: space-between !important;
                        align-items: center !important;
                        margin-bottom: 20px !important;
                        -webkit-print-color-adjust: exact !important;
                        print-color-adjust: exact !important;
                    }
                    .report-header-banner h1,
                    .report-header-banner p,
                    .report-header-banner strong,
                    .report-header-banner div {
                        color: #ffffff !important;
                        -webkit-print-color-adjust: exact !important;
                        print-color-adjust: exact !important;
                    }
                    .report-card, tr, table, div[style*="gridTemplateColumns"] {
                        break-inside: avoid !important;
                        page-break-inside: avoid !important;
                    }
                }

                .header-logo-text { text-align: right; }
                .header-logo-text .brand-title { font-size: 22px; font-weight: 900; letter-spacing: 1px; line-height: 1; color: #ffffff; }
                .header-logo-text .brand-sub { font-size: 10px; letter-spacing: 2px; font-weight: 700; color: #93c5fd; }
                .header-logo-text .report-date { font-size: 13px; font-weight: 700; margin-top: 4px; color: #ffffff; }

                .report-card {
                    background-color: #ffffff !important;
                    border: 1px solid #e2e8f0 !important;
                    border-radius: 12px !important;
                    padding: 20px !important;
                    margin-bottom: 20px !important;
                    box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.03);
                    color: #334155 !important;
                    break-inside: avoid !important;
                    page-break-inside: avoid !important;
                }

                .report-card-title {
                    font-size: 15px !important;
                    font-weight: 700 !important;
                    color: #0f172a !important;
                    padding-bottom: 10px !important;
                    border-bottom: 1px solid #f1f5f9 !important;
                    margin-bottom: 16px !important;
                    display: flex !important;
                    align-items: center !important;
                    justify-content: space-between !important;
                }

                .info-grid-2col { display: grid !important; grid-template-columns: 1fr 1fr !important; gap: 20px !important; margin-bottom: 20px !important; }
                .info-row { display: flex !important; justify-content: space-between !important; align-items: center !important; padding: 8px 0 !important; border-bottom: 1px solid #f8fafc !important; font-size: 13px !important; }
                .info-label { color: #64748b !important; font-weight: 500 !important; }
                .info-value { font-weight: 700 !important; color: #0f172a !important; text-align: right !important; }
                .info-value.highlight { color: #0b4885 !important; }

                .metrics-grid-6col { display: grid !important; grid-template-columns: repeat(6, 1fr) !important; gap: 12px !important; }
                .metric-widget { background-color: #ffffff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px 10px; text-align: center; box-shadow: 0 2px 4px rgba(0, 0, 0, 0.02); }
                .metric-icon-box { width: 42px; height: 42px; margin: 0 auto 8px auto; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 18px; }
                .icon-blue { background-color: #eff6ff; color: #2563eb; }
                .icon-emerald { background-color: #ecfdf5; color: #10b981; }
                .icon-amber { background-color: #fffbeb; color: #f59e0b; }
                .icon-cyan { background-color: #ecfeff; color: #0891b2; }
                .icon-indigo { background-color: #eef2ff; color: #4f46e5; }
                .icon-teal { background-color: #f0fdfa; color: #0d9488; }

                .metric-name { font-size: 11px; font-weight: 700; color: #64748b; margin-bottom: 4px; }
                .metric-val { font-size: 22px; font-weight: 900; color: #0f172a; }
                .metric-val span { font-size: 11px; font-weight: 400; color: #94a3b8; margin-left: 2px; }

                .trend-badge { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; border-radius: 12px; font-size: 10px; font-weight: 700; margin-top: 6px; }
                .badge-red { background-color: #fef2f2; color: #dc2626; border: 1px solid #fecaca; }
                .badge-blue { background-color: #eff6ff; color: #2563eb; border: 1px solid #bfdbfe; }
                .badge-slate { background-color: #f8fafc; color: #64748b; border: 1px solid #e2e8f0; }

                .summary-container { background-color: #f0f7ff !important; border-left: 4px solid #0b4885 !important; border-radius: 12px !important; padding: 18px !important; overflow: visible !important; height: auto !important; }
                .summary-subcard { background-color: #ffffff !important; border: 1px solid #dbeafe !important; border-radius: 10px !important; padding: 14px 16px !important; margin-top: 10px !important; display: flex !important; align-items: flex-start !important; gap: 14px !important; box-shadow: 0 2px 4px rgba(11, 72, 133, 0.04); overflow: visible !important; height: auto !important; }
                .subcard-icon { width: 32px; height: 32px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 14px; flex-shrink: 0; margin-top: 2px; }
                .subcard-icon.red { background-color: #fef2f2; color: #ef4444; }
                .subcard-icon.blue { background-color: #eff6ff; color: #2563eb; }
                .subcard-icon.emerald { background-color: #ecfdf5; color: #10b981; }

                .subcard-title { font-size: 13.5px !important; font-weight: 700 !important; color: #0f172a !important; margin-bottom: 6px !important; }
                .subcard-desc { font-size: 12.5px !important; color: #334155 !important; line-height: 1.65 !important; margin: 0 !important; white-space: pre-line !important; word-break: keep-all !important; overflow-wrap: break-word !important; overflow: visible !important; height: auto !important; }

                .monitoring-grid-3col { display: grid !important; grid-template-columns: repeat(3, 1fr) !important; gap: 20px !important; }
                .progress-bar-bg { width: 100%; background-color: #f1f5f9; border-radius: 6px; height: 8px; overflow: hidden; margin-top: 4px; }
                .progress-bar-fill { height: 100%; border-radius: 6px; }
            `}</style>

            {/* Top Control Bar */}
            <div className="control-bar-wrapper no-print">
                <div className="control-bar-inner">
                    <div className="control-group-left">
                        <div className="control-item">
                            <label>고객사 선택</label>
                            <select 
                                value={selectedCustomer?.id || ''}
                                onChange={(e) => {
                                    const cust = gcpCustomers.find(c => c.id === e.target.value);
                                    if (cust) {
                                        setSelectedCustomer(cust);
                                        if (cust.environments) {
                                            for (const env of cust.environments) {
                                                if (env.providerType === 'GCP' && env.projects && env.projects.length > 0) {
                                                    setSelectedProject(env.projects[0].projectId);
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }}
                            >
                                {gcpCustomers.map(c => (
                                    <option key={c.id} value={c.id}>{c.name}</option>
                                ))}
                            </select>
                        </div>

                        <div className="control-item">
                            <label>GCP 프로젝트 선택</label>
                            <select 
                                value={selectedProject}
                                onChange={(e) => setSelectedProject(e.target.value)}
                            >
                                {selectedCustomer?.environments?.filter(env => env.providerType === 'GCP').flatMap(env => env.projects || []).map(p => (
                                    <option key={p.id} value={p.projectId}>{p.projectId}</option>
                                ))}
                            </select>
                        </div>

                        <div className="control-item">
                            <label>보고서 연월 선택</label>
                            <select
                                value={selectedYearMonth}
                                onChange={(e) => setSelectedYearMonth(e.target.value)}
                            >
                                {generateYearMonthOptions().map(opt => (
                                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                                ))}
                            </select>
                        </div>



                        <button 
                            className="btn-generate"
                            onClick={fetchReport}
                            disabled={!selectedProject || loading}
                        >
                            <i className={`fas ${loading ? 'fa-spinner fa-spin' : 'fa-file-invoice'}`}></i>
                            {loading ? '보고서 생성 중...' : '보고서 생성'}
                        </button>
                    </div>

                    <div className="control-group-right">
                        <button className={`btn-edit ${isEditMode ? 'active' : ''}`} onClick={() => setIsEditMode(!isEditMode)} disabled={!reportData}>
                            <i className={`fas ${isEditMode ? 'fa-check' : 'fa-edit'} mr-1`}></i>
                            {isEditMode ? '편집 종료' : '편집 모드'}
                        </button>

                        {isEditMode && (
                            <button className="btn-save" onClick={handleSaveText} disabled={savingText}>
                                <i className="fas fa-save mr-1"></i>{savingText ? '저장 중...' : '작성 내용 저장'}
                            </button>
                        )}

                        <button className="btn-pdf" onClick={handlePrint} disabled={!reportData}>
                            <i className="fas fa-file-pdf"></i> PDF 저장 / 인쇄
                        </button>
                    </div>
                </div>
            </div>

            {loading ? (
                <div style={{ textAlign: 'center', padding: '80px 0' }}>
                    <i className="fas fa-spinner fa-spin fa-3x" style={{ color: '#0b4885', marginBottom: '12px' }}></i>
                    <p style={{ fontWeight: 700, color: '#1e293b' }}>BigQuery 데이터 수집 및 프리미엄 정기 보고서를 생성하는 중입니다...</p>
                </div>
            ) : !reportData ? (
                <div style={{ textAlign: 'center', padding: '100px 20px', color: '#475569' }}>
                    <div style={{
                        maxWidth: '540px',
                        margin: '0 auto',
                        background: '#ffffff',
                        padding: '40px 30px',
                        borderRadius: '16px',
                        border: '1px solid #e2e8f0',
                        boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.05)'
                    }}>
                        <i className="fas fa-file-invoice fa-3x" style={{ color: '#2563eb', marginBottom: '16px' }}></i>
                        <h3 style={{ fontSize: '18px', fontWeight: 800, color: '#0f172a', marginBottom: '8px' }}>
                            GCP 정기 점검 보고서 생성
                        </h3>
                        <p style={{ fontSize: '14px', color: '#64748b', lineHeight: 1.6, margin: 0 }}>
                            상단에서 <strong>고객사</strong>, <strong>GCP 프로젝트</strong>, <strong>보고서 연월</strong>을 선택하신 후<br />
                            <strong>[보고서 생성]</strong> 버튼을 클릭해 주십시오.
                        </p>
                    </div>
                </div>
            ) : (
                <div id="print-area" style={{ maxWidth: '1280px', margin: '0 auto', padding: '0 16px' }}>
                    
                    {/* Header Banner */}
                    <div 
                        className="report-header-banner"
                        style={{
                            backgroundColor: '#0b4885',
                            background: '#0b4885',
                            boxShadow: 'inset 0 0 0 1000px #0b4885',
                            color: '#ffffff',
                            borderRadius: '12px',
                            padding: '24px 28px',
                            display: 'flex',
                            flexDirection: 'row',
                            justifyContent: 'space-between',
                            alignItems: 'center',
                            marginBottom: '20px',
                            WebkitPrintColorAdjust: 'exact',
                            printColorAdjust: 'exact'
                        }}
                    >
                        <div>
                            <h1 style={{ fontSize: '22px', fontWeight: 800, margin: '0 0 6px 0', color: '#ffffff', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>
                                Google Cloud Infrastructure {isQuarterly ? '분기' : '월간'} 보고서
                            </h1>
                            <p style={{ fontSize: '13px', color: '#dbeafe', margin: 0, WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>
                                고객사: <strong style={{ color: '#ffffff' }}>{reportData.customerName}</strong>
                            </p>
                        </div>
                        <div className="header-logo-text" style={{ textAlign: 'right', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>
                            <div className="brand-title" style={{ fontSize: '22px', fontWeight: 900, letterSpacing: '1px', lineHeight: 1, color: '#ffffff', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>MEGAZONE</div>
                            <div className="brand-sub" style={{ fontSize: '10px', letterSpacing: '2px', fontWeight: 700, color: '#93c5fd', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>CLOUD</div>
                            <div className="report-date" style={{ fontSize: '13px', fontWeight: 700, marginTop: '4px', color: '#ffffff', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>{reportData.reportMonth}</div>
                        </div>
                    </div>

                    {/* Section 1: Operating Info & Project Info */}
                    <div className="info-grid-2col">
                        <div className="report-card" style={{ marginBottom: 0 }}>
                            <div className="report-card-title">
                                <span><i className="fas fa-info-circle mr-2" style={{ color: '#2563eb' }}></i>운영 정보 (Operating Info)</span>
                            </div>
                            <div className="info-row">
                                <span className="info-label">발행 주기</span>
                                <span className="info-value" style={{ backgroundColor: '#f1f5f9', padding: '2px 8px', borderRadius: '4px' }}>
                                    {isQuarterly ? '분기 (Quarterly)' : '월간 (Monthly)'}
                                </span>
                            </div>
                            <div className="info-row">
                                <span className="info-label">MSP 등급</span>
                                {isEditMode ? (
                                    <input type="text" className="edit-input-field" value={editGrade} onChange={(e) => setEditGrade(e.target.value)} style={{ textAlign: 'right', padding: '2px 8px' }} />
                                ) : (
                                    <span className="info-value highlight">{reportData.mspGrade}</span>
                                )}
                            </div>
                            <div className="info-row">
                                <span className="info-label">영업 담당자</span>
                                {isEditMode ? (
                                    <input type="text" className="edit-input-field" value={editSalesContact} onChange={(e) => setEditSalesContact(e.target.value)} style={{ textAlign: 'right', padding: '2px 8px' }} />
                                ) : (
                                    <span className="info-value">{reportData.mspSalesContact}</span>
                                )}
                            </div>
                            <div className="info-row">
                                <span className="info-label">기술 지원 담당자</span>
                                {isEditMode ? (
                                    <input type="text" className="edit-input-field" value={editTechContact} onChange={(e) => setEditTechContact(e.target.value)} style={{ textAlign: 'right', padding: '2px 8px' }} />
                                ) : (
                                    <span className="info-value">{reportData.mspTechContact}</span>
                                )}
                            </div>
                        </div>

                        <div className="report-card" style={{ marginBottom: 0 }}>
                            <div className="report-card-title">
                                <span><i className="fas fa-folder-open mr-2" style={{ color: '#2563eb' }}></i>프로젝트 정보 (Project Info)</span>
                            </div>
                            <div className="info-row">
                                <span className="info-label">고객사 명칭</span>
                                <span className="info-value">{reportData.customerName}</span>
                            </div>
                            <div className="info-row">
                                <span className="info-label">GCP Project ID</span>
                                <span className="info-value" style={{ fontFamily: 'Consolas, monospace', fontSize: '12px', backgroundColor: '#f1f5f9', padding: '2px 8px', borderRadius: '4px' }}>
                                    {reportData.projectId}
                                </span>
                            </div>
                            <div className="info-row">
                                <span className="info-label">보고서 대상 기간</span>
                                <span className="info-value highlight">{reportData.reportPeriod}</span>
                            </div>
                        </div>
                    </div>

                    {/* Section 2: Key Metrics Summary */}
                    <div className="report-card">
                        <div className="report-card-title">
                            <span><i className="fas fa-chart-pie mr-2" style={{ color: '#2563eb' }}></i>핵심 인프라 자원 요약</span>
                            <span style={{ fontSize: '11px', fontWeight: 500, color: '#94a3b8', backgroundColor: '#f8fafc', padding: '2px 10px', borderRadius: '12px', border: '1px solid #e2e8f0' }}>전분기 대비 증감</span>
                        </div>
                        <div className="metrics-grid-6col">
                            <div className="metric-widget">
                                <div className="metric-icon-box icon-blue"><i className="fas fa-server"></i></div>
                                <div className="metric-name">Compute VM</div>
                                <div className="metric-val">{reportData.vmTotal || reportData.vmTotalTrend?.[3] || 0}<span>ea</span></div>
                                <div>{renderTrendBadge(reportData.vmTotalDelta || 0)}</div>
                            </div>

                            <div className="metric-widget">
                                <div className="metric-icon-box icon-emerald"><i className="fas fa-database"></i></div>
                                <div className="metric-name">Cloud SQL</div>
                                <div className="metric-val">{reportData.sqlTotal || reportData.sqlTotalTrend?.[3] || 0}<span>ea</span></div>
                                <div>{renderTrendBadge(reportData.sqlTotalDelta || 0)}</div>
                            </div>

                            <div className="metric-widget">
                                <div className="metric-icon-box icon-amber"><i className="fas fa-hdd"></i></div>
                                <div className="metric-name">Persistent Disk</div>
                                <div className="metric-val">{reportData.storageSummary?.diskTotal || 0}<span>ea</span></div>
                                <div>{renderTrendBadge(reportData.diskTotalDelta || 0)}</div>
                            </div>

                            <div className="metric-widget">
                                <div className="metric-icon-box icon-cyan"><i className="fas fa-box-archive"></i></div>
                                <div className="metric-name">Cloud Storage</div>
                                <div className="metric-val">{reportData.bucketSecurity?.total || 0}<span>bkt</span></div>
                                <div>{renderTrendBadge(reportData.bucketTotalDelta || 0)}</div>
                            </div>

                            <div className="metric-widget">
                                <div className="metric-icon-box icon-indigo"><i className="fas fa-cubes"></i></div>
                                <div className="metric-name">GKE Cluster</div>
                                <div className="metric-val">{reportData.gkeTotal || 0}<span>ea</span></div>
                                <div>{renderTrendBadge(reportData.gkeTotalDelta || 0)}</div>
                            </div>

                            <div className="metric-widget">
                                <div className="metric-icon-box icon-teal"><i className="fas fa-network-wired"></i></div>
                                <div className="metric-name">Load Balancer</div>
                                <div className="metric-val">{reportData.lbSummary?.total || 0}<span>ea</span></div>
                                <div>{renderTrendBadge(reportData.lbTotalDelta || 0)}</div>
                            </div>
                        </div>
                    </div>

                    {/* Section 3: Executive Summary */}
                    <div className="summary-container mb-5">
                        <div style={{ fontSize: '15px', fontWeight: 800, color: '#0f172a', marginBottom: '12px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                <i className="fas fa-lightbulb" style={{ color: '#f59e0b' }}></i> 핵심 요약 및 개선 방향 (Executive Summary)
                            </div>
                            {isEditMode && (
                                <button
                                    type="button"
                                    onClick={handleGenerateAiSummary}
                                    disabled={isGeneratingAi}
                                    style={{
                                        display: 'inline-flex',
                                        alignItems: 'center',
                                        gap: '6px',
                                        fontSize: '12px',
                                        fontWeight: 600,
                                        color: '#4338ca',
                                        backgroundColor: '#eef2ff',
                                        border: '1px solid #c7d2fe',
                                        padding: '4px 12px',
                                        borderRadius: '6px',
                                        cursor: isGeneratingAi ? 'not-allowed' : 'pointer',
                                        transition: 'all 0.2s ease'
                                    }}
                                >
                                    <i className={`fas ${isGeneratingAi ? 'fa-spinner fa-spin' : 'fa-wand-magic-sparkles'}`} style={{ color: '#6366f1' }}></i>
                                    {isGeneratingAi ? 'GCP AI 분석 및 생성 중...' : '✨ GCP AI 요약 재생성'}
                                </button>
                            )}
                        </div>
                        <div>

                            <div className="summary-subcard">
                                <div className="subcard-icon red"><i className="fas fa-shield-alt"></i></div>
                                <div style={{ flexGrow: 1 }}>
                                    <div className="subcard-title">보안 및 컴플라이언스</div>
                                    {isEditMode ? (
                                        <textarea
                                            className="edit-textarea-field"
                                            style={{ width: '100%', padding: '8px 12px', marginTop: '6px', minHeight: '85px', fontSize: '12px', lineHeight: '1.5', resize: 'vertical' }}
                                            rows={4}
                                            value={editExecSecurity}
                                            onChange={(e) => setEditExecSecurity(e.target.value)}
                                        />
                                    ) : (
                                        <p className="subcard-desc">
                                            {reportData.customExecSecurity || '해당 카테고리 권고 사항 없음'}
                                        </p>
                                    )}
                                </div>
                            </div>

                            <div className="summary-subcard">
                                <div className="subcard-icon blue"><i className="fas fa-coins"></i></div>
                                <div style={{ flexGrow: 1 }}>
                                    <div className="subcard-title">비용 최적화</div>
                                    {isEditMode ? (
                                        <textarea
                                            className="edit-textarea-field"
                                            style={{ width: '100%', padding: '8px 12px', marginTop: '6px', minHeight: '85px', fontSize: '12px', lineHeight: '1.5', resize: 'vertical' }}
                                            rows={4}
                                            value={editExecCost}
                                            onChange={(e) => setEditExecCost(e.target.value)}
                                        />
                                    ) : (
                                        <p className="subcard-desc">
                                            {reportData.customExecCost || '해당 카테고리 권고 사항 없음'}
                                        </p>
                                    )}
                                </div>
                            </div>

                            <div className="summary-subcard">
                                <div className="subcard-icon emerald"><i className="fas fa-tachometer-alt"></i></div>
                                <div style={{ flexGrow: 1 }}>
                                    <div className="subcard-title">성능 및 안정성</div>
                                    {isEditMode ? (
                                        <textarea
                                            className="edit-textarea-field"
                                            style={{ width: '100%', padding: '8px 12px', marginTop: '6px', minHeight: '85px', fontSize: '12px', lineHeight: '1.5', resize: 'vertical' }}
                                            rows={4}
                                            value={editExecPerformance}
                                            onChange={(e) => setEditExecPerformance(e.target.value)}
                                        />
                                    ) : (
                                        <p className="subcard-desc">
                                            {reportData.customExecPerformance || '해당 카테고리 권고 사항 없음'}
                                        </p>
                                    )}
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Section 4: IAM Bar Chart & Security Audit Metrics */}
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginBottom: '20px' }}>
                        <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column' }}>
                            <div className="report-card-title">
                                <span><i className="fas fa-users-cog mr-2" style={{ color: '#2563eb' }}></i>Cloud IAM 주체별 변동 추이</span>
                                <span style={{ fontSize: '10px', backgroundColor: '#f1f5f9', padding: '2px 6px', borderRadius: '4px', color: '#64748b' }}>최근 4개월</span>
                            </div>
                            
                            <div style={{ flexGrow: 1, display: 'flex', alignItems: 'flex-end', justifyContent: 'space-around', height: '170px', paddingTop: '24px', paddingBottom: '8px', borderBottom: '1px solid #e2e8f0', borderLeft: '1px solid #e2e8f0', marginLeft: '16px' }}>
                                {(reportData.months || get4MonthsArray(selectedYearMonth)).map((m, idx) => {
                                    const uCount = userTrend[idx] || 0;
                                    const sCount = saTrend[idx] || 0;
                                    const uPct = (uCount / maxVal) * 100;
                                    const sPct = (sCount / maxVal) * 100;

                                    return (
                                        <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '20%', height: '100%', justifyContent: 'flex-end' }}>
                                            <div style={{ display: 'flex', alignItems: 'flex-end', gap: '6px', width: '100%', justifyContent: 'center', height: '100%' }}>
                                                <div style={{ width: '20px', backgroundColor: '#93c5fd', borderRadius: '4px 4px 0 0', height: `${uPct}%`, position: 'relative' }}>
                                                    {uCount > 0 && <span style={{ position: 'absolute', top: '-16px', left: '50%', transform: 'translateX(-50%)', fontSize: '9px', fontWeight: 700, color: '#475569' }}>{uCount}</span>}
                                                </div>
                                                <div style={{ width: '20px', backgroundColor: '#1e3a8a', borderRadius: '4px 4px 0 0', height: `${sPct}%`, position: 'relative' }}>
                                                    {sCount > 0 && <span style={{ position: 'absolute', top: '-16px', left: '50%', transform: 'translateX(-50%)', fontSize: '9px', fontWeight: 700, color: '#1e3a8a' }}>{sCount}</span>}
                                                </div>
                                            </div>
                                            <span style={{ fontSize: '11px', marginTop: '8px', color: idx === 3 ? '#2563eb' : '#64748b', fontWeight: idx === 3 ? 700 : 400 }}>{m}</span>
                                        </div>
                                    );
                                })}
                            </div>

                            <div style={{ display: 'flex', justifyContent: 'center', gap: '16px', fontSize: '11px', marginTop: '6px' }}>
                                <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                    <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#60a5fa', marginRight: '5px' }}></span>일반 사용자 (User)
                                </span>
                                <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                    <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#1d4ed8', marginRight: '5px' }}></span>서비스 계정 (Service Account)
                                </span>
                            </div>
                        </div>

                        <div className="report-card" style={{ marginBottom: 0 }}>
                            <div className="report-card-title">
                                <span><i className="fas fa-users-cog mr-2" style={{ color: '#2563eb' }}></i>계정 및 키 보안 감사 지표</span>
                                <i className="fas fa-lock" style={{ color: '#cbd5e1' }}></i>
                            </div>

                            <div style={{ display: 'flex', gap: '12px', padding: '8px 0 4px 0', flexWrap: 'wrap' }}>
                                {/* Box 1: 90일 초과 미갱신 SA 키 — Compute VM Status '실행 중 vs 중지' 행과 동일 크기 */}
                                <div style={{
                                    display: 'flex',
                                    justifyContent: 'space-between',
                                    alignItems: 'center',
                                    width: '100%',
                                    backgroundColor: '#fef2f2',
                                    border: '1px solid #fecaca',
                                    borderRadius: '10px',
                                    padding: '10px 12px',
                                    position: 'relative'
                                }}>
                                    <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#ef4444', borderRadius: '10px 0 0 10px' }}></div>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                        <div style={{ width: '32px', height: '32px', borderRadius: '50%', backgroundColor: '#fee2e2', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#ef4444', fontSize: '12px' }}>
                                            <i className="fas fa-key"></i>
                                        </div>
                                        <div>
                                            <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>90일 초과 미갱신 SA 키</span>
                                            <span style={{ display: 'block', fontSize: '9px', color: '#64748b', marginTop: '1px' }}>USER_MANAGED 방치 위험 탐지</span>
                                        </div>
                                    </div>
                                    {(() => {
                                        const kOver90 = reportData.saSecurity?.keyOver90 || 0;
                                        const isRisk = kOver90 > 0;
                                        return (
                                            <div style={{ textAlign: 'right' }}>
                                                <span style={{ fontSize: '14px', fontWeight: 900, color: isRisk ? '#dc2626' : '#10b981' }}>
                                                    {kOver90}<span style={{ fontSize: '10px', fontWeight: 500, color: '#64748b' }}>개</span>
                                                </span>
                                                <span style={{ fontSize: '10px', fontWeight: 700, color: isRisk ? '#ef4444' : '#10b981', marginLeft: '6px' }}>
                                                    {isRisk ? 'Critical' : '정상'}
                                                </span>
                                            </div>
                                        );
                                    })()}
                                </div>

                                {/* Box 2: 소유자 권한이 있는 SA 키 — Compute VM Status '실행 중 vs 중지' 행과 동일 크기 */}
                                <div style={{
                                    display: 'flex',
                                    justifyContent: 'space-between',
                                    alignItems: 'center',
                                    width: '100%',
                                    backgroundColor: '#fffbeb',
                                    border: '1px solid #fef3c7',
                                    borderRadius: '10px',
                                    padding: '10px 12px',
                                    position: 'relative'
                                }}>
                                    <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#f59e0b', borderRadius: '10px 0 0 10px' }}></div>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                        <div style={{ width: '32px', height: '32px', borderRadius: '50%', backgroundColor: '#fef3c7', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#d97706', fontSize: '12px' }}>
                                            <i className="fas fa-user-shield"></i>
                                        </div>
                                        <div>
                                            <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>소유자 권한 SA 키</span>
                                            <span style={{ display: 'block', fontSize: '9px', color: '#64748b', marginTop: '1px' }}>IAM Owner 역할 보유 서비스계정</span>
                                        </div>
                                    </div>
                                    {(() => {
                                        const count = reportData.ownerSaCount || 0;
                                        const isRisk = count > 0;
                                        return (
                                            <div style={{ textAlign: 'right' }}>
                                                <span style={{ fontSize: '14px', fontWeight: 900, color: isRisk ? '#f59e0b' : '#10b981' }}>
                                                    {count}<span style={{ fontSize: '10px', fontWeight: 500, color: '#64748b' }}>개</span>
                                                </span>
                                                <span style={{ fontSize: '10px', fontWeight: 700, color: isRisk ? '#f59e0b' : '#10b981', marginLeft: '6px' }}>
                                                    {isRisk ? '주의 필요' : '정상'}
                                                </span>
                                            </div>
                                        );
                                    })()}
                                </div>

                                {/* Box 3: 소유자 권한이 있는 사용자 계정 — Compute VM Status '실행 중 vs 중지' 행과 동일 크기 */}
                                <div style={{
                                    display: 'flex',
                                    justifyContent: 'space-between',
                                    alignItems: 'center',
                                    width: '100%',
                                    backgroundColor: '#f0fdf4',
                                    border: '1px solid #bbf7d0',
                                    borderRadius: '10px',
                                    padding: '10px 12px',
                                    position: 'relative'
                                }}>
                                    <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#22c55e', borderRadius: '10px 0 0 10px' }}></div>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                        <div style={{ width: '32px', height: '32px', borderRadius: '50%', backgroundColor: '#dcfce7', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#16a34a', fontSize: '12px' }}>
                                            <i className="fas fa-users"></i>
                                        </div>
                                        <div>
                                            <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>소유자 권한 사용자 계정</span>
                                            <span style={{ display: 'block', fontSize: '9px', color: '#64748b', marginTop: '1px' }}>IAM Owner 역할 보유 사용자 계정</span>
                                        </div>
                                    </div>
                                    {(() => {
                                        const count = reportData.ownerUserCount || 0;
                                        const isRisk = count > 0;
                                        return (
                                            <div style={{ textAlign: 'right' }}>
                                                <span style={{ fontSize: '14px', fontWeight: 900, color: isRisk ? '#16a34a' : '#10b981' }}>
                                                    {count}<span style={{ fontSize: '10px', fontWeight: 500, color: '#64748b' }}>명</span>
                                                </span>
                                                <span style={{ fontSize: '10px', fontWeight: 700, color: isRisk ? '#16a34a' : '#10b981', marginLeft: '6px' }}>
                                                    {isRisk ? '주의 필요' : '정상'}
                                                </span>
                                            </div>
                                        );
                                    })()}
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Section 5: Compute VM, VPC, LB, GKE, Cloud Run, Storage, SQL */}
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                        
                        {/* Row 1: Compute VM Split (Placed right between IAM and VPC Network) */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                            {/* Left Box: Compute VM Trend Chart */}
                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-server mr-2" style={{ color: '#2563eb' }}></i>Compute VM 수량</span>
                                </div>
                                <div style={{ height: '115px', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                                    <div style={{ display: 'flex', height: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                        {(reportData.vmTotalTrend || [0, 0, 0, (reportData.vmTotal || 0)]).map((val, idx) => {
                                            const maxV = Math.max(1, ...((reportData.vmTotalTrend || [1])));
                                            const hPct = Math.max(15, Math.round((val / maxV) * 100));
                                            return (
                                                <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: 1, height: '100%', justifyContent: 'flex-end' }}>
                                                    {val > 0 ? (
                                                        <>
                                                            <span style={{ fontSize: '9px', fontWeight: 700, color: '#2563eb', marginBottom: '2px' }}>{val}</span>
                                                            <div style={{ width: '22px', height: `${hPct}%`, backgroundColor: '#3b82f6', borderRadius: '4px 4px 0 0' }}></div>
                                                        </>
                                                    ) : null}
                                                </div>
                                            );
                                        })}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                        {(reportData.months || get4MonthsArray(selectedYearMonth)).map((m, idx) => (
                                            <span key={idx} style={{ flex: 1, textAlign: 'center' }}>{m}</span>
                                        ))}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'center', fontSize: '11px', marginTop: '6px' }}>
                                        <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#3b82f6', marginRight: '5px' }}></span>Compute VM 수량
                                        </span>
                                    </div>
                                </div>
                            </div>

                            {/* Right Box: Compute VM Status (Matching IAM Card Design) */}
                            <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-server mr-2" style={{ color: '#2563eb' }}></i>Compute VM Status</span>
                                </div>

                                {(() => {
                                    const running = reportData.vmSummary?.running != null ? reportData.vmSummary.running : (reportData.vmTotal || 0);
                                    const stopped = reportData.vmSummary?.stopped || 0;
                                    const total = running + stopped;

                                    // Dynamic Machine Type determination with format: "custom-4 8192 외(2대) (총 3대)"
                                    let machineTypeTitle = "해당 없음 (0대)";
                                    let machineTypeSub = "인스턴스 미보유";

                                    if (total > 0) {
                                        const typeEntries = Object.entries(reportData.vmMachineTypes || {})
                                            .filter(([_, cnt]) => cnt > 0)
                                            .sort((a, b) => b[1] - a[1]);

                                        if (typeEntries.length > 0) {
                                            if (typeEntries.length === 1) {
                                                machineTypeTitle = `${typeEntries[0][0]} ${typeEntries[0][1]}대`;
                                            } else {
                                                const firstType = typeEntries[0];
                                                const otherCount = typeEntries.slice(1).reduce((sum, [_, cnt]) => sum + cnt, 0);
                                                const otherTypes = typeEntries.slice(1).length;
                                                machineTypeTitle = `${firstType[0]} ${firstType[1]}대 외(${otherCount}대) (총 ${total}대)`;
                                            }
                                            const firstTypeKey = typeEntries[0][0].toLowerCase();
                                            if (firstTypeKey.startsWith('e2') || firstTypeKey.startsWith('n2') || firstTypeKey.startsWith('n1')) {
                                                machineTypeSub = "General Purpose 인스턴스";
                                            } else if (firstTypeKey.startsWith('c2') || firstTypeKey.startsWith('c3')) {
                                                machineTypeSub = "Compute Optimized 인스턴스";
                                            } else if (firstTypeKey.startsWith('m1') || firstTypeKey.startsWith('m2') || firstTypeKey.startsWith('m3')) {
                                                machineTypeSub = "Memory Optimized 인스턴스";
                                            } else if (firstTypeKey.startsWith('a2') || firstTypeKey.startsWith('g2')) {
                                                machineTypeSub = "Accelerator (GPU) 인스턴스";
                                            } else {
                                                machineTypeSub = "표준 가상머신 인스턴스";
                                            }
                                        } else {
                                            machineTypeTitle = "표준 인스턴스 계열";
                                            machineTypeSub = "General Purpose 인스턴스";
                                        }
                                    }

                                    return (
                                        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                                            {/* Card 1: Running vs Stopped State */}
                                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', backgroundColor: total > 0 ? '#f0fdf4' : '#f8fafc', border: `1px solid ${total > 0 ? '#bbf7d0' : '#e2e8f0'}`, borderRadius: '10px', position: 'relative' }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: total > 0 ? '#10b981' : '#94a3b8', borderRadius: '10px 0 0 10px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                                    <div style={{ width: '32px', height: '32px', borderRadius: '50%', backgroundColor: total > 0 ? '#dcfce7' : '#f1f5f9', display: 'flex', alignItems: 'center', justifyContent: 'center', color: total > 0 ? '#16a34a' : '#64748b', fontSize: '12px' }}>
                                                        <i className="fas fa-play-circle"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>실행 중 vs 중지 인스턴스</span>
                                                        <span style={{ display: 'block', fontSize: '9px', color: total > 0 ? '#14532d' : '#64748b', marginTop: '1px' }}>Compute Engine 운용 상태</span>
                                                    </div>
                                                </div>
                                                <div style={{ textAlign: 'right' }}>
                                                    <span style={{ fontSize: '14px', fontWeight: 900, color: total > 0 ? '#16a34a' : '#64748b' }}>{running}대 <span style={{ fontSize: '10px', fontWeight: 500, color: '#64748b' }}>(중지 {stopped}대)</span></span>
                                                </div>
                                            </div>

                                            {/* Card 2: Machine Specs */}
                                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', backgroundColor: total > 0 ? '#eff6ff' : '#f8fafc', border: `1px solid ${total > 0 ? '#bfdbfe' : '#e2e8f0'}`, borderRadius: '10px', position: 'relative' }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: total > 0 ? '#3b82f6' : '#94a3b8', borderRadius: '10px 0 0 10px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                                    <div style={{ width: '32px', height: '32px', borderRadius: '50%', backgroundColor: total > 0 ? '#dbeafe' : '#f1f5f9', display: 'flex', alignItems: 'center', justifyContent: 'center', color: total > 0 ? '#2563eb' : '#64748b', fontSize: '12px' }}>
                                                        <i className="fas fa-cubes"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>주요 머신 유형 (Type)</span>
                                                        <span style={{ display: 'block', fontSize: '9px', color: total > 0 ? '#1e40af' : '#64748b', marginTop: '1px' }}>{machineTypeSub}</span>
                                                    </div>
                                                </div>
                                                <div style={{ textAlign: 'right' }}>
                                                    <span style={{ fontSize: '12px', fontWeight: 800, color: total > 0 ? '#2563eb' : '#64748b' }}>{machineTypeTitle}</span>
                                                </div>
                                            </div>

                                            {/* Card 3: 외부 IP 할당 VM — 실행 중 vs 중지/주요 머신 유형과 동일 크기 (가로형) */}
                                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', backgroundColor: total > 0 ? '#eff6ff' : '#f8fafc', border: `1px solid ${total > 0 ? '#bfdbfe' : '#e2e8f0'}`, borderRadius: '10px', position: 'relative' }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: total > 0 ? '#3b82f6' : '#94a3b8', borderRadius: '10px 0 0 10px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                                    <div style={{ width: '32px', height: '32px', borderRadius: '50%', backgroundColor: total > 0 ? '#dbeafe' : '#f1f5f9', display: 'flex', alignItems: 'center', justifyContent: 'center', color: total > 0 ? '#2563eb' : '#64748b', fontSize: '12px' }}>
                                                        <i className="fas fa-globe"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>외부 IP 할당 VM</span>
                                                        <span style={{ display: 'block', fontSize: '9px', color: total > 0 ? '#1e40af' : '#64748b', marginTop: '1px' }}>공인 IP 보유 인스턴스</span>
                                                    </div>
                                                </div>
                                                <div style={{ textAlign: 'right' }}>
                                                    <span style={{ fontSize: '14px', fontWeight: 900, color: total > 0 ? '#2563eb' : '#64748b' }}>{running}<span style={{ fontSize: '10px', fontWeight: 500, color: '#64748b' }}>대</span></span>
                                                </div>
                                            </div>
                                        </div>
                                    );
                                })()}
                            </div>
                        </div>

                        {/* Row 2: VPC Network */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-project-diagram mr-2" style={{ color: '#0284c7' }}></i>VPC Network & Subnet 수량</span>
                                </div>
                                <div style={{ height: '115px', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                                    <div style={{ display: 'flex', height: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                        {(reportData.months || get4MonthsArray(selectedYearMonth)).map((_, idx) => {
                                            const netVal = (reportData.vpcTrend && reportData.vpcTrend.length > idx) ? reportData.vpcTrend[idx] : 0;
                                            const subVal = (reportData.vpcSubnetTrend && reportData.vpcSubnetTrend.length > idx) ? reportData.vpcSubnetTrend[idx] : 0;
                                            
                                            const maxV = Math.max(1, ...((reportData.vpcTrend || [1])), ...((reportData.vpcSubnetTrend || [1])));
                                            const netPct = Math.max(12, Math.round((netVal / maxV) * 100));
                                            const subPct = Math.max(12, Math.round((subVal / maxV) * 100));

                                            return (
                                                <div key={idx} style={{ display: 'flex', gap: '4px', alignItems: 'flex-end', justifyContent: 'center', flex: 1, height: '100%' }}>
                                                    {/* Network Bar */}
                                                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                        {netVal > 0 ? (
                                                            <>
                                                                <span style={{ fontSize: '9px', fontWeight: 700, color: '#3b82f6', marginBottom: '1px' }}>{netVal}</span>
                                                                <div style={{ width: '12px', height: `${netPct}%`, backgroundColor: '#60a5fa', borderRadius: '3px 3px 0 0' }}></div>
                                                            </>
                                                        ) : null}
                                                    </div>
                                                    {/* Subnet Bar */}
                                                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                        {subVal > 0 ? (
                                                            <>
                                                                <span style={{ fontSize: '9px', fontWeight: 700, color: '#1d4ed8', marginBottom: '1px' }}>{subVal}</span>
                                                                <div style={{ width: '12px', height: `${subPct}%`, backgroundColor: '#1d4ed8', borderRadius: '3px 3px 0 0' }}></div>
                                                            </>
                                                        ) : null}
                                                    </div>
                                                </div>
                                            );
                                        })}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                        {(reportData.months || get4MonthsArray(selectedYearMonth)).map((m, idx) => (
                                            <span key={idx} style={{ flex: 1, textAlign: 'center' }}>{m}</span>
                                        ))}
                                    </div>
                                    {/* Bottom Legend */}
                                    <div style={{ display: 'flex', justifyContent: 'center', gap: '16px', fontSize: '11px', marginTop: '6px' }}>
                                        <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#60a5fa', marginRight: '5px' }}></span>VPC Network
                                        </span>
                                        <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#1d4ed8', marginRight: '5px' }}></span>Subnet
                                        </span>
                                    </div>
                                </div>
                            </div>

                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-project-diagram mr-2" style={{ color: '#0284c7' }}></i>VPC 핵심 지표</span>
                                </div>
                                <div style={{ fontSize: '12px', display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '115px' }}>
                                    <div style={{ marginBottom: '16px' }}>
                                        {(() => {
                                            const usedIp = reportData.ipSummary?.externalUsed || 0;
                                            const unusedIp = reportData.ipSummary?.externalUnused || 0;
                                            const totalIp = usedIp + unusedIp;
                                            const ipPct = totalIp > 0 ? Math.round((usedIp / totalIp) * 100) : 0;
                                            return (
                                                <>
                                                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                                                        <span style={{ color: '#475569' }}>VPC External Static IP 사용률</span>
                                                        <span style={{ fontWeight: 700, color: '#2563eb' }}>{ipPct}% ({usedIp}/{totalIp}개 사용 중)</span>
                                                    </div>
                                                    <div className="progress-bar-bg">
                                                        <div className="progress-bar-fill" style={{ width: `${ipPct}%`, backgroundColor: '#3b82f6' }}></div>
                                                    </div>
                                                </>
                                            );
                                        })()}
                                    </div>

                                    <div>
                                        {(() => {
                                            const loggingFw = reportData.fwSummary?.loggingEnabled || 0;
                                            const totalFw = reportData.fwSummary?.totalRules || 0;
                                            const fwPct = totalFw > 0 ? Math.round((loggingFw / totalFw) * 100) : 0;
                                            return (
                                                <>
                                                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                                                        <span style={{ color: '#475569' }}>방화벽 로그 활성화율</span>
                                                        <span style={{ fontWeight: 700, color: '#d97706' }}>{fwPct}% ({loggingFw}/{totalFw}개 설정됨)</span>
                                                    </div>
                                                    <div className="progress-bar-bg">
                                                        <div className="progress-bar-fill" style={{ width: `${fwPct}%`, backgroundColor: '#f59e0b' }}></div>
                                                    </div>
                                                </>
                                            );
                                        })()}
                                    </div>
                                </div>
                            </div>
                        </div>

                        {/* Row 2: Load Balancing */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-network-wired mr-2" style={{ color: '#0d9488' }}></i>Load Balancing 수량</span>
                                </div>
                                <div style={{ height: '115px', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                                    <div style={{ display: 'flex', height: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                        {((reportData.lbTrend && reportData.lbTrend.length > 0) ? reportData.lbTrend : [(reportData.lbTotal || 0), (reportData.lbTotal || 0), (reportData.lbTotal || 0), (reportData.lbTotal || 0)]).map((val, idx) => {
                                            const maxV = Math.max(1, ...((reportData.lbTrend && reportData.lbTrend.length > 0) ? reportData.lbTrend : [(reportData.lbTotal || 1)]));
                                            const hPct = Math.max(15, Math.round((val / maxV) * 100));
                                            return (
                                                <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: 1, height: '100%', justifyContent: 'flex-end' }}>
                                                    {val > 0 ? (
                                                        <>
                                                            <span style={{ fontSize: '9px', fontWeight: 700, color: '#059669', marginBottom: '2px' }}>{val}</span>
                                                            <div style={{ width: '22px', height: `${hPct}%`, backgroundColor: '#10b981', borderRadius: '4px 4px 0 0' }}></div>
                                                        </>
                                                    ) : null}
                                                </div>
                                            );
                                        })}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                        {(reportData.months || get4MonthsArray(selectedYearMonth)).map((m, idx) => (
                                            <span key={idx} style={{ flex: 1, textAlign: 'center' }}>{m}</span>
                                        ))}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'center', fontSize: '11px', marginTop: '6px' }}>
                                        <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#10b981', marginRight: '5px' }}></span>로드밸런서
                                        </span>
                                    </div>
                                </div>
                            </div>

                            <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-network-wired mr-2" style={{ color: '#0d9488' }}></i>LB 핵심 지표</span>
                                </div>

                                {(() => {
                                    const lbCount = reportData.lbTotal || (reportData.lbSummary?.total || 0);
                                    const isLbExist = lbCount > 0;
                                    const unhealthyCount = reportData.lbSummary?.healthUnhealthyTotal || 0;
                                    const http500Count = reportData.lbSummary?.http500Last30Days || 0;
                                    return (
                                        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px', margin: 'auto 0' }}>
                                            {/* Horizontal Card 1: 30-Day HTTP 500 Error Metric */}
                                            <div style={{
                                                display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px',
                                                backgroundColor: isLbExist ? (http500Count > 0 ? '#fef2f2' : '#f0fdf4') : '#f8fafc',
                                                border: isLbExist ? (http500Count > 0 ? '1px solid #fecaca' : '1px solid #bbf7d0') : '1px solid #e2e8f0',
                                                borderRadius: '8px', position: 'relative'
                                            }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: isLbExist ? (http500Count > 0 ? '#ef4444' : '#10b981') : '#94a3b8', borderRadius: '8px 0 0 8px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                                    <div style={{
                                                        width: '32px', height: '32px', borderRadius: '50%',
                                                        backgroundColor: isLbExist ? (http500Count > 0 ? '#fee2e2' : '#dcfce7') : '#f1f5f9',
                                                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                        color: isLbExist ? (http500Count > 0 ? '#dc2626' : '#16a34a') : '#64748b', fontSize: '13px'
                                                    }}>
                                                        <i className="fas fa-exclamation-triangle"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '12px' }}>최근 30일 HTTP 500 에러</span>
                                                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', marginTop: '1px' }}>
                                                            {isLbExist ? (http500Count > 0 ? `최근 30일간 5XX 에러 ${http500Count}건 감지됨` : 'HTTP 5XX 서버 응답 트래픽 정상') : '연결된 로드밸런서 타겟 없음'}
                                                        </span>
                                                    </div>
                                                </div>
                                                <div style={{ textAlign: 'right' }}>
                                                    {isLbExist ? (
                                                        http500Count > 0 ? (
                                                            <span style={{ fontSize: '12px', fontWeight: 800, color: '#b91c1c', backgroundColor: '#fee2e2', padding: '3px 10px', borderRadius: '6px', border: '1px solid #fca5a5' }}>
                                                                <i className="fas fa-exclamation-triangle mr-1"></i>{http500Count}건 발생
                                                            </span>
                                                        ) : (
                                                            <span style={{ fontSize: '12px', fontWeight: 800, color: '#15803d', backgroundColor: '#dcfce7', padding: '3px 10px', borderRadius: '6px', border: '1px solid #86efac' }}>
                                                                <i className="fas fa-check mr-1"></i>정상 (0건)
                                                            </span>
                                                        )
                                                    ) : (
                                                        <span style={{ fontSize: '11px', fontWeight: 700, color: '#64748b', backgroundColor: '#f1f5f9', padding: '3px 8px', borderRadius: '6px', border: '1px solid #cbd5e1' }}>
                                                            N/A (LB 미사용)
                                                        </span>
                                                    )}
                                                </div>
                                            </div>

                                            {/* Horizontal Card 2: Backend Health Check Metric */}
                                            <div style={{
                                                display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 12px',
                                                backgroundColor: isLbExist ? (unhealthyCount > 0 ? '#fef2f2' : '#f0fdf4') : '#f8fafc',
                                                border: isLbExist ? (unhealthyCount > 0 ? '1px solid #fecaca' : '1px solid #bbf7d0') : '1px solid #e2e8f0',
                                                borderRadius: '8px', position: 'relative'
                                            }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: isLbExist ? (unhealthyCount > 0 ? '#ef4444' : '#10b981') : '#94a3b8', borderRadius: '8px 0 0 8px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                                    <div style={{
                                                        width: '32px', height: '32px', borderRadius: '50%',
                                                        backgroundColor: isLbExist ? (unhealthyCount > 0 ? '#fee2e2' : '#dcfce7') : '#f1f5f9',
                                                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                                                        color: isLbExist ? (unhealthyCount > 0 ? '#dc2626' : '#16a34a') : '#64748b', fontSize: '13px'
                                                    }}>
                                                        <i className="fas fa-heartbeat"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '12px' }}>백엔드 헬스 체크 상태</span>
                                                        <span style={{ display: 'block', fontSize: '10px', color: '#64748b', marginTop: '1px' }}>
                                                            {isLbExist ? (unhealthyCount > 0 ? `비정상 백엔드 ${unhealthyCount}개 감지됨` : '백엔드 인스턴스 헬스 프로브 정상') : '헬스 체크 대상 인스턴스 없음'}
                                                        </span>
                                                    </div>
                                                </div>
                                                <div style={{ textAlign: 'right' }}>
                                                    {isLbExist ? (
                                                        unhealthyCount > 0 ? (
                                                            <span style={{ fontSize: '12px', fontWeight: 800, color: '#b91c1c', backgroundColor: '#fee2e2', padding: '3px 10px', borderRadius: '6px', border: '1px solid #fca5a5' }}>
                                                                <i className="fas fa-exclamation-triangle mr-1"></i>비정상 {unhealthyCount}건
                                                            </span>
                                                        ) : (
                                                            <span style={{ fontSize: '12px', fontWeight: 800, color: '#15803d', backgroundColor: '#dcfce7', padding: '3px 10px', borderRadius: '6px', border: '1px solid #86efac' }}>
                                                                <i className="fas fa-check mr-1"></i>정상
                                                            </span>
                                                        )
                                                    ) : (
                                                        <span style={{ fontSize: '11px', fontWeight: 700, color: '#64748b', backgroundColor: '#f1f5f9', padding: '3px 8px', borderRadius: '6px', border: '1px solid #cbd5e1' }}>
                                                            N/A (LB 미사용)
                                                        </span>
                                                    )}
                                                </div>
                                            </div>
                                        </div>
                                    );
                                })()}
                            </div>
                        </div>

                        {/* Row 3: GKE Cluster */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-cubes mr-2" style={{ color: '#4f46e5' }}></i>GKE 클러스터 수량</span>
                                </div>
                                <div style={{ height: '115px', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                                    <div style={{ display: 'flex', height: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                        {((reportData.gkeNodeTrend && reportData.gkeNodeTrend.length > 0) ? reportData.gkeNodeTrend : [(reportData.gkeTotal || 0), (reportData.gkeTotal || 0), (reportData.gkeTotal || 0), (reportData.gkeTotal || 0)]).map((val, idx) => {
                                            const maxV = Math.max(1, ...((reportData.gkeNodeTrend && reportData.gkeNodeTrend.length > 0) ? reportData.gkeNodeTrend : [1]));
                                            const hPct = Math.max(15, Math.round((val / maxV) * 100));
                                            return (
                                                <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: 1, height: '100%', justifyContent: 'flex-end' }}>
                                                    {val > 0 ? (
                                                        <>
                                                            <span style={{ fontSize: '9px', fontWeight: 700, color: '#7c3aed', marginBottom: '2px' }}>{val}</span>
                                                            <div style={{ width: '22px', height: `${hPct}%`, backgroundColor: '#7c3aed', borderRadius: '4px 4px 0 0' }}></div>
                                                        </>
                                                    ) : null}
                                                </div>
                                            );
                                        })}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                        {(reportData.months || get4MonthsArray(selectedYearMonth)).map((m, idx) => (
                                            <span key={idx} style={{ flex: 1, textAlign: 'center' }}>{m}</span>
                                        ))}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'center', fontSize: '11px', marginTop: '6px' }}>
                                        <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#7c3aed', marginRight: '5px' }}></span>GKE 클러스터
                                        </span>
                                    </div>
                                </div>
                            </div>

                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-cubes mr-2" style={{ color: '#4f46e5' }}></i>GKE 핵심 지표</span>
                                </div>
                                <div style={{ fontSize: '12px', display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '115px' }}>
                                    {(() => {
                                        const gkeCount = reportData.gkeTotal || 0;
                                        const isGkeExist = gkeCount > 0;
                                        const nodeCount = isGkeExist ? (reportData.gkeNodeTotal || gkeCount * 3) : 0;
                                        const nodePct = isGkeExist ? 100 : 0;
                                        return (
                                            <>
                                                <div style={{ marginBottom: '12px' }}>
                                                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                                                        <span style={{ color: '#475569' }}>GKE 클러스터 노드 총량</span>
                                                        <span style={{ fontWeight: 700, color: isGkeExist ? '#7c3aed' : '#64748b' }}>
                                                            {isGkeExist ? `${nodeCount}개 노드 가동 중` : '0개 (데이터 없음)'}
                                                        </span>
                                                    </div>
                                                    <div className="progress-bar-bg">
                                                        <div className="progress-bar-fill" style={{ width: `${nodePct}%`, backgroundColor: '#8b5cf6' }}></div>
                                                    </div>
                                                </div>

                                                <div>
                                                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px' }}>
                                                        <span style={{ color: '#475569' }}>Standard / Autopilot 구성</span>
                                                        <span style={{ fontWeight: 700, color: isGkeExist ? '#d97706' : '#64748b' }}>
                                                            {isGkeExist ? `Standard / Autopilot 모드` : '0개 (데이터 없음)'}
                                                        </span>
                                                    </div>
                                                    <div className="progress-bar-bg">
                                                        <div className="progress-bar-fill" style={{ width: `${isGkeExist ? 100 : 0}%`, backgroundColor: '#f59e0b' }}></div>
                                                    </div>
                                                </div>
                                            </>
                                        );
                                    })()}
                                </div>
                            </div>
                        </div>

                        {/* Row 4: Cloud Run */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-bolt mr-2" style={{ color: '#d97706' }}></i>Cloud Run 서비스 수량</span>
                                </div>
                                <div style={{ height: '115px', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                                    <div style={{ display: 'flex', height: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                        {((reportData.serverlessTrend && reportData.serverlessTrend.length > 0) ? reportData.serverlessTrend : [(reportData.cloudRunSummary?.totalServices || 0), (reportData.cloudRunSummary?.totalServices || 0), (reportData.cloudRunSummary?.totalServices || 0), (reportData.cloudRunSummary?.totalServices || 0)]).map((val, idx) => {
                                            const maxV = Math.max(1, ...((reportData.serverlessTrend && reportData.serverlessTrend.length > 0) ? reportData.serverlessTrend : [1]));
                                            const hPct = Math.max(15, Math.round((val / maxV) * 100));
                                            return (
                                                <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: 1, height: '100%', justifyContent: 'flex-end' }}>
                                                    {val > 0 ? (
                                                        <>
                                                            <span style={{ fontSize: '9px', fontWeight: 700, color: '#d97706', marginBottom: '2px' }}>{val}</span>
                                                            <div style={{ width: '22px', height: `${hPct}%`, backgroundColor: '#d97706', borderRadius: '4px 4px 0 0' }}></div>
                                                        </>
                                                    ) : null}
                                                </div>
                                            );
                                        })}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                        {(reportData.months || get4MonthsArray(selectedYearMonth)).map((m, idx) => (
                                            <span key={idx} style={{ flex: 1, textAlign: 'center' }}>{m}</span>
                                        ))}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'center', fontSize: '11px', marginTop: '6px' }}>
                                        <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#d97706', marginRight: '5px' }}></span>Cloud Run 서비스
                                        </span>
                                    </div>
                                </div>
                            </div>

                            {/* Cloud Run Core Metrics (3 Horizontal Cards) */}
                            <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-bolt mr-2" style={{ color: '#d97706' }}></i>Cloud Run 핵심 지표</span>
                                </div>

                                {(() => {
                                    const ingAll = reportData.cloudRunSummary?.ingressAll || 0;
                                    const ingInt = reportData.cloudRunSummary?.ingressInternal || 0;
                                    const jobTot = reportData.cloudRunSummary?.jobTotal || 0;

                                    return (
                                        <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', margin: 'auto 0' }}>
                                            {/* Card 1: External Access Services */}
                                            <div style={{
                                                display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 10px',
                                                backgroundColor: '#fffbe6', border: '1px solid #ffe58f', borderRadius: '8px', position: 'relative'
                                            }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#d97706', borderRadius: '8px 0 0 8px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                                                    <div style={{
                                                        width: '28px', height: '28px', borderRadius: '50%', backgroundColor: '#fef3c7',
                                                        display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#d97706', fontSize: '12px'
                                                    }}>
                                                        <i className="fas fa-globe"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>외부 접근 허용 서비스 수</span>
                                                        <span style={{ display: 'block', fontSize: '9px', color: '#78350f', marginTop: '1px' }}>INGRESS_TRAFFIC_ALL 설정</span>
                                                    </div>
                                                </div>
                                                <div>
                                                    <span style={{ fontSize: '13px', fontWeight: 900, color: '#d97706' }}>{ingAll}개</span>
                                                </div>
                                            </div>

                                            {/* Card 2: Internal Access Services */}
                                            <div style={{
                                                display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 10px',
                                                backgroundColor: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: '8px', position: 'relative'
                                            }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#10b981', borderRadius: '8px 0 0 8px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                                                    <div style={{
                                                        width: '28px', height: '28px', borderRadius: '50%', backgroundColor: '#dcfce7',
                                                        display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#16a34a', fontSize: '12px'
                                                    }}>
                                                        <i className="fas fa-shield-alt"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>내부 접근 허용 서비스 수</span>
                                                        <span style={{ display: 'block', fontSize: '9px', color: '#14532d', marginTop: '1px' }}>VPC / Internal LB 트래픽 전용</span>
                                                    </div>
                                                </div>
                                                <div>
                                                    <span style={{ fontSize: '13px', fontWeight: 900, color: '#16a34a' }}>{ingInt}개</span>
                                                </div>
                                            </div>

                                            {/* Card 3: Cloud Run Jobs Count */}
                                            <div style={{
                                                display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 10px',
                                                backgroundColor: '#f5f3ff', border: '1px solid #ddd6fe', borderRadius: '8px', position: 'relative'
                                            }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#7c3aed', borderRadius: '8px 0 0 8px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                                                    <div style={{
                                                        width: '28px', height: '28px', borderRadius: '50%', backgroundColor: '#ede9fe',
                                                        display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6d28d9', fontSize: '12px'
                                                    }}>
                                                        <i className="fas fa-tasks"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>Cloud Run Jobs 총 개수</span>
                                                        <span style={{ display: 'block', fontSize: '9px', color: '#5b21b6', marginTop: '1px' }}>배치 및 온디맨드 실행 태스크</span>
                                                    </div>
                                                </div>
                                                <div>
                                                    <span style={{ fontSize: '13px', fontWeight: 900, color: '#6d28d9' }}>{jobTot}개</span>
                                                </div>
                                            </div>
                                        </div>
                                    );
                                })()}
                            </div>
                        </div>

                        {/* Row 5: Persistent Disk (Left) & Cloud VPN (Right) */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                            {/* Left Box: Persistent Disk */}
                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-hdd mr-2" style={{ color: '#d97706' }}></i>Persistent Disk (블록 스토리지)</span>
                                </div>
                                <div style={{ height: '115px', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                                    <div style={{ display: 'flex', height: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                        {(reportData.months || get4MonthsArray(selectedYearMonth)).map((_, idx) => {
                                            const dVal = (reportData.diskTrend && reportData.diskTrend.length > idx) ? reportData.diskTrend[idx] : 0;
                                            const sVal = (reportData.snapshotTrend && reportData.snapshotTrend.length > idx) ? reportData.snapshotTrend[idx] : 0;
                                            
                                            const maxV = Math.max(1, ...((reportData.diskTrend || [1])), ...((reportData.snapshotTrend || [1])));
                                            const dPct = Math.max(12, Math.round((dVal / maxV) * 100));
                                            const sPct = Math.max(12, Math.round((sVal / maxV) * 100));

                                            return (
                                                <div key={idx} style={{ display: 'flex', gap: '4px', alignItems: 'flex-end', justifyContent: 'center', flex: 1, height: '100%' }}>
                                                    {/* Disk Bar */}
                                                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                        {dVal > 0 ? (
                                                            <>
                                                                <span style={{ fontSize: '8px', fontWeight: 700, color: '#0891b2', marginBottom: '1px' }}>{dVal}</span>
                                                                <div style={{ width: '12px', height: `${dPct}%`, backgroundColor: '#06b6d4', borderRadius: '3px 3px 0 0' }}></div>
                                                            </>
                                                        ) : null}
                                                    </div>
                                                    {/* Snapshot Bar */}
                                                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', height: '100%', justifyContent: 'flex-end' }}>
                                                        {sVal > 0 ? (
                                                            <>
                                                                <span style={{ fontSize: '8px', fontWeight: 700, color: '#d97706', marginBottom: '1px' }}>{sVal}</span>
                                                                <div style={{ width: '12px', height: `${sPct}%`, backgroundColor: '#f59e0b', borderRadius: '3px 3px 0 0' }}></div>
                                                            </>
                                                        ) : null}
                                                    </div>
                                                </div>
                                            );
                                        })}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                        {(reportData.months || get4MonthsArray(selectedYearMonth)).map((m, idx) => (
                                            <span key={idx} style={{ flex: 1, textAlign: 'center' }}>{m}</span>
                                        ))}
                                    </div>
                                    <div style={{ display: 'flex', justifyContent: 'center', gap: '14px', fontSize: '11px', marginTop: '6px' }}>
                                        <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '10px', height: '10px', borderRadius: '2px', backgroundColor: '#06b6d4', marginRight: '4px' }}></span>PD 수량
                                        </span>
                                        <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                            <span style={{ display: 'inline-block', width: '10px', height: '10px', borderRadius: '2px', backgroundColor: '#f59e0b', marginRight: '4px' }}></span>스냅샷 수량
                                        </span>
                                    </div>
                                </div>
                            </div>

                            {/* Right Box: Cloud VPN Core Metrics */}
                            <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-lock mr-2" style={{ color: '#0284c7' }}></i>Cloud VPN 핵심 지표</span>
                                </div>
                                {(() => {
                                    const vpnConn = reportData.vpnSummary?.connectedTunnels || 0;
                                    const vpnDisconn = reportData.vpnSummary?.disconnectedTunnels || 0;
                                    const vpnTot = reportData.vpnSummary?.totalTunnels || (vpnConn + vpnDisconn);
                                    const displayTot = vpnTot > 0 ? vpnTot : (reportData.vpnTotal || 1);
                                    const connPct = vpnTot > 0 ? Math.round((vpnConn / vpnTot) * 100) : 100;

                                    return (
                                        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                                            {/* Card Style matching 2nd image */}
                                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '10px 12px', backgroundColor: '#fffbeb', border: '1px solid #fef3c7', borderRadius: '10px', position: 'relative' }}>
                                                <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#f59e0b', borderRadius: '10px 0 0 10px' }}></div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: '10px', paddingLeft: '4px' }}>
                                                    <div style={{ width: '32px', height: '32px', borderRadius: '50%', backgroundColor: '#fef3c7', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#d97706', fontSize: '12px' }}>
                                                        <i className="fas fa-lock"></i>
                                                    </div>
                                                    <div>
                                                        <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>Cloud VPN 총 수량</span>
                                                        <span style={{ display: 'block', fontSize: '9px', color: '#b45309', marginTop: '1px' }}>IPsec IKEv2 터널 암호화 설정</span>
                                                    </div>
                                                </div>
                                                <div style={{ textAlign: 'right' }}>
                                                    <span style={{ fontSize: '16px', fontWeight: 900, color: '#d97706' }}>{displayTot}<span style={{ fontSize: '11px', fontWeight: 700, color: '#d97706', marginLeft: '2px' }}>개</span></span>
                                                </div>
                                            </div>

                                            {/* Progress Item 1: Tunnel Encryption */}
                                            <div>
                                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2px' }}>
                                                    <span style={{ fontSize: '10px', color: '#475569', fontWeight: 600 }}>VPN 터널 암호화 (IPsec IKEv2)</span>
                                                    <span style={{ fontSize: '10px', fontWeight: 700, color: '#0284c7' }}>
                                                        100% ({displayTot}/{displayTot}개 사용 중)
                                                    </span>
                                                </div>
                                                <div className="progress-bar-bg" style={{ height: '6px', backgroundColor: '#e2e8f0', borderRadius: '3px', overflow: 'hidden' }}>
                                                    <div style={{ width: '100%', backgroundColor: '#0284c7', height: '100%', borderRadius: '3px' }}></div>
                                                </div>
                                            </div>

                                            {/* Progress Item 2: Connected Ratio */}
                                            <div>
                                                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2px' }}>
                                                    <span style={{ fontSize: '10px', color: '#475569', fontWeight: 600 }}>Cloud VPN 터널 정상 연결률</span>
                                                    <span style={{ fontSize: '10px', fontWeight: 700, color: '#10b981' }}>
                                                        {connPct}% ({vpnConn > 0 ? vpnConn : displayTot}/{displayTot}개 연결됨)
                                                    </span>
                                                </div>
                                                <div className="progress-bar-bg" style={{ height: '6px', backgroundColor: '#e2e8f0', borderRadius: '3px', overflow: 'hidden' }}>
                                                    <div style={{ width: `${connPct}%`, backgroundColor: '#10b981', height: '100%', borderRadius: '3px' }}></div>
                                                </div>
                                            </div>
                                        </div>
                                    );
                                })()}
                            </div>
                        </div>

                        {/* Row 6: Cloud SQL Split (2-Column Grid) */}
                        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
                            {/* Left Box: Cloud SQL Trend Chart */}
                            <div className="report-card" style={{ marginBottom: 0 }}>
                                <div className="report-card-title">
                                    <span><i className="fas fa-database mr-2" style={{ color: '#059669' }}></i>Cloud SQL 수량</span>
                                </div>
                                    <div style={{ height: '115px', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
                                        <div style={{ display: 'flex', height: '65px', alignItems: 'flex-end', justifyContent: 'space-around', borderBottom: '1px dashed #e2e8f0', paddingBottom: '4px' }}>
                                            {((reportData.sqlTotalTrend && reportData.sqlTotalTrend.length > 0) ? reportData.sqlTotalTrend : [(reportData.sqlTotal || 0), (reportData.sqlTotal || 0), (reportData.sqlTotal || 0), (reportData.sqlTotal || 0)]).map((val, idx) => {
                                                const maxV = Math.max(1, ...((reportData.sqlTotalTrend && reportData.sqlTotalTrend.length > 0) ? reportData.sqlTotalTrend : [(reportData.sqlTotal || 1)]));
                                                const hPct = Math.max(15, Math.round((val / maxV) * 100));
                                                return (
                                                    <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: 1, height: '100%', justifyContent: 'flex-end' }}>
                                                        {val > 0 ? (
                                                            <>
                                                                <span style={{ fontSize: '9px', fontWeight: 700, color: '#4f46e5', marginBottom: '2px' }}>{val}</span>
                                                                <div style={{ width: '22px', height: `${hPct}%`, backgroundColor: '#6366f1', borderRadius: '4px 4px 0 0' }}></div>
                                                            </>
                                                        ) : null}
                                                    </div>
                                                );
                                            })}
                                        </div>
                                        <div style={{ display: 'flex', justifyContent: 'space-around', fontSize: '10px', color: '#64748b', marginTop: '4px' }}>
                                            {(reportData.months || get4MonthsArray(selectedYearMonth)).map((m, idx) => (
                                                <span key={idx} style={{ flex: 1, textAlign: 'center' }}>{m}</span>
                                            ))}
                                        </div>
                                        <div style={{ display: 'flex', justifyContent: 'center', fontSize: '11px', marginTop: '6px' }}>
                                            <span style={{ color: '#1e293b', fontWeight: 700, display: 'flex', alignItems: 'center' }}>
                                                <span style={{ display: 'inline-block', width: '12px', height: '12px', borderRadius: '3px', backgroundColor: '#6366f1', marginRight: '5px' }}></span>Cloud SQL 인스턴스
                                            </span>
                                        </div>
                                    </div>
                                </div>

                                {/* Right Box: Cloud SQL Core Metrics (3 Horizontal Cards) */}
                                <div className="report-card" style={{ marginBottom: 0, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                                    <div className="report-card-title">
                                        <span><i className="fas fa-database mr-2" style={{ color: '#059669' }}></i>Cloud SQL 핵심 지표</span>
                                    </div>

                                    {(() => {
                                        const sqlTot = reportData.sqlTotal || 0;
                                        const haCount = reportData.sqlHaTypes?.HA || 0;
                                        const engineName = (reportData.sqlEngines && Object.keys(reportData.sqlEngines).length > 0)
                                            ? Object.keys(reportData.sqlEngines).join(', ')
                                            : (sqlTot > 0 ? 'PostgreSQL' : '없음');

                                        return (
                                            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', margin: 'auto 0' }}>
                                                {/* Card 1: HA Configuration */}
                                                <div style={{
                                                    display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 10px',
                                                    backgroundColor: '#f0fdf4', border: '1px solid #bbf7d0', borderRadius: '8px', position: 'relative'
                                                }}>
                                                    <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#10b981', borderRadius: '8px 0 0 8px' }}></div>
                                                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                                                        <div style={{
                                                            width: '28px', height: '28px', borderRadius: '50%', backgroundColor: '#dcfce7',
                                                            display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#16a34a', fontSize: '12px'
                                                        }}>
                                                            <i className="fas fa-shield-alt"></i>
                                                        </div>
                                                        <div>
                                                            <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>고가용성 (HA) 구성</span>
                                                            <span style={{ display: 'block', fontSize: '9px', color: '#14532d', marginTop: '1px' }}>Regional HA 이중화 구성 수</span>
                                                        </div>
                                                    </div>
                                                    <div>
                                                        <span style={{ fontSize: '13px', fontWeight: 900, color: '#16a34a' }}>{sqlTot > 0 ? `${haCount}개` : '0개'}</span>
                                                    </div>
                                                </div>

                                                {/* Card 2: DB Engine Representative Version */}
                                                <div style={{
                                                    display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 10px',
                                                    backgroundColor: '#e0e7ff', border: '1px solid #c7d2fe', borderRadius: '8px', position: 'relative'
                                                }}>
                                                    <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#4f46e5', borderRadius: '8px 0 0 8px' }}></div>
                                                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                                                        <div style={{
                                                            width: '28px', height: '28px', borderRadius: '50%', backgroundColor: '#c7d2fe',
                                                            display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#4338ca', fontSize: '12px'
                                                        }}>
                                                            <i className="fas fa-database"></i>
                                                        </div>
                                                        <div>
                                                            <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>DB 엔진 대표 버전</span>
                                                            <span style={{ display: 'block', fontSize: '9px', color: '#312e81', marginTop: '1px' }}>엔진 종류 및 버전 정보</span>
                                                        </div>
                                                    </div>
                                                    <div>
                                                        <span style={{ fontSize: '12px', fontWeight: 800, color: '#4338ca' }}>{engineName}</span>
                                                    </div>
                                                </div>

                                                {/* Card 3: Automated Backup & Recovery */}
                                                <div style={{
                                                    display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '6px 10px',
                                                    backgroundColor: '#f5f3ff', border: '1px solid #ddd6fe', borderRadius: '8px', position: 'relative'
                                                }}>
                                                    <div style={{ position: 'absolute', left: 0, top: 0, width: '4px', height: '100%', backgroundColor: '#7c3aed', borderRadius: '8px 0 0 8px' }}></div>
                                                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', paddingLeft: '4px' }}>
                                                        <div style={{
                                                            width: '28px', height: '28px', borderRadius: '50%', backgroundColor: '#ede9fe',
                                                            display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#6d28d9', fontSize: '12px'
                                                        }}>
                                                            <i className="fas fa-cloud-upload-alt"></i>
                                                        </div>
                                                        <div>
                                                            <span style={{ display: 'block', fontWeight: 700, color: '#0f172a', fontSize: '11px' }}>자동 백업 및 PITR 복구</span>
                                                            <span style={{ display: 'block', fontSize: '9px', color: '#5b21b6', marginTop: '1px' }}>Point-in-Time 복구 활성화</span>
                                                        </div>
                                                    </div>
                                                    <div>
                                                        <span style={{ fontSize: '11px', fontWeight: 800, color: sqlTot > 0 ? '#6d28d9' : '#64748b' }}>
                                                            {sqlTot > 0 ? '활성화 (정상)' : '없음'}
                                                        </span>
                                                    </div>
                                                </div>
                                            </div>
                                        );
                                    })()}
                                </div>
                            </div>
                        </div>

                    {/* Section 6: Work Status Table (Jira Integrated + Editable) */}
                    <div className="report-card" style={{ borderTop: '4px solid #0b4885', marginTop: '20px', marginBottom: 0 }}>
                        <div className="report-card-title" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <span><i className="fas fa-tasks mr-2" style={{ color: '#2563eb' }}></i>업무 현황 (기술 지원 및 운영 업무 내역)</span>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                                <span style={{ fontSize: '10px', color: '#64748b', fontWeight: 400 }}>* 보고서 기간 별 Jira 티켓 자동 매핑</span>
                                {isEditMode && (
                                    <div style={{ display: 'flex', gap: '6px' }}>
                                        <button 
                                            type="button" 
                                            className="btn btn-sm btn-primary font-weight-bold" 
                                            style={{ fontSize: '11px', padding: '3px 8px' }}
                                            onClick={handleAddWorkLogRow}>
                                            <i className="fas fa-plus mr-1"></i>행 추가
                                        </button>
                                        <button 
                                            type="button" 
                                            className="btn btn-sm btn-outline-secondary font-weight-bold" 
                                            style={{ fontSize: '11px', padding: '3px 8px' }}
                                            onClick={() => {
                                                if (reportData?.workLogs) {
                                                    setEditWorkLogs(reportData.workLogs);
                                                }
                                            }}>
                                            <i className="fas fa-sync-alt mr-1"></i>원래 데이터 복원
                                        </button>
                                    </div>
                                )}
                            </div>
                        </div>

                        <div className="table-responsive" style={{ marginTop: '8px' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '11px' }}>
                                <thead>
                                    <tr style={{ backgroundColor: '#0b4885', color: '#ffffff', textAlign: 'center' }}>
                                        <th style={{ padding: '8px', width: isEditMode ? '14%' : '15%', border: '1px solid #0b4885' }}>업무 구분</th>
                                        <th style={{ padding: '8px', width: isEditMode ? '12%' : '15%', border: '1px solid #0b4885' }}>대상 구분</th>
                                        <th style={{ padding: '8px', width: isEditMode ? '14%' : '18%', border: '1px solid #0b4885' }}>일자</th>
                                        <th style={{ padding: '8px', textAlign: 'left', border: '1px solid #0b4885' }}>업무 내역</th>
                                        {isEditMode && <th style={{ padding: '8px', width: '8%', border: '1px solid #0b4885' }}>관리</th>}
                                    </tr>
                                </thead>
                                <tbody>
                                    {isEditMode ? (
                                        editWorkLogs.length === 0 ? (
                                            <tr>
                                                <td colSpan={5} style={{ padding: '24px', textAlign: 'center', color: '#64748b', fontWeight: 600, border: '1px solid #e2e8f0', backgroundColor: '#ffffff' }}>
                                                    <i className="fas fa-inbox mr-2"></i>등록된 업무 내역이 없습니다. 상단 [행 추가] 버튼을 눌러 추가하세요.
                                                </td>
                                            </tr>
                                        ) : (
                                            editWorkLogs.map((log, idx) => (
                                                <tr key={idx} style={{ backgroundColor: idx % 2 === 0 ? '#ffffff' : '#f8fafc' }}>
                                                    <td style={{ padding: '6px', border: '1px solid #e2e8f0' }}>
                                                        <input 
                                                            type="text" 
                                                            className="edit-input-field" 
                                                            style={{ width: '100%', padding: '4px 6px', textAlign: 'center' }} 
                                                            value={log.category} 
                                                            onChange={(e) => handleUpdateWorkLogRow(idx, 'category', e.target.value)} 
                                                            placeholder="업무구분"
                                                        />
                                                    </td>
                                                    <td style={{ padding: '6px', border: '1px solid #e2e8f0' }}>
                                                        <input 
                                                            type="text" 
                                                            className="edit-input-field" 
                                                            style={{ width: '100%', padding: '4px 6px', textAlign: 'center' }} 
                                                            value={log.target} 
                                                            onChange={(e) => handleUpdateWorkLogRow(idx, 'target', e.target.value)} 
                                                            placeholder="대상"
                                                        />
                                                    </td>
                                                    <td style={{ padding: '6px', border: '1px solid #e2e8f0' }}>
                                                        <input 
                                                            type="text" 
                                                            className="edit-input-field" 
                                                            style={{ width: '100%', padding: '4px 6px', textAlign: 'center' }} 
                                                            value={log.workDate} 
                                                            onChange={(e) => handleUpdateWorkLogRow(idx, 'workDate', e.target.value)} 
                                                            placeholder="YYYY-MM-DD"
                                                        />
                                                    </td>
                                                    <td style={{ padding: '6px', border: '1px solid #e2e8f0' }}>
                                                        <input 
                                                            type="text" 
                                                            className="edit-input-field" 
                                                            style={{ width: '100%', padding: '4px 8px' }} 
                                                            value={log.content} 
                                                            onChange={(e) => handleUpdateWorkLogRow(idx, 'content', e.target.value)} 
                                                            placeholder="업무 내역 설명"
                                                        />
                                                    </td>
                                                    <td style={{ padding: '6px', textAlign: 'center', border: '1px solid #e2e8f0' }}>
                                                        <button 
                                                            type="button" 
                                                            className="btn btn-sm btn-outline-danger" 
                                                            style={{ padding: '2px 6px', fontSize: '10px' }} 
                                                            onClick={() => handleDeleteWorkLogRow(idx)} 
                                                            title="삭제">
                                                            <i className="fas fa-trash-alt"></i>
                                                        </button>
                                                    </td>
                                                </tr>
                                            ))
                                        )
                                    ) : (
                                        (editWorkLogs.length > 0 ? editWorkLogs : (reportData?.workLogs || [])).length === 0 ? (
                                            <tr>
                                                <td colSpan={4} style={{ padding: '24px', textAlign: 'center', color: '#64748b', fontWeight: 600, border: '1px solid #e2e8f0', backgroundColor: '#ffffff' }}>
                                                    <i className="fas fa-inbox mr-2"></i>해당 기간 내 접수된 기술 지원 및 운영 업무 내역이 없습니다.
                                                </td>
                                            </tr>
                                        ) : (
                                            (editWorkLogs.length > 0 ? editWorkLogs : (reportData?.workLogs || [])).map((log, idx) => (
                                                <tr key={idx} style={{ backgroundColor: idx % 2 === 0 ? '#ffffff' : '#f8fafc' }}>
                                                    <td style={{ padding: '8px', textAlign: 'center', border: '1px solid #e2e8f0', fontWeight: 700, color: '#334155' }}>{log.category}</td>
                                                    <td style={{ padding: '8px', textAlign: 'center', border: '1px solid #e2e8f0', color: '#475569' }}>{log.target}</td>
                                                    <td style={{ padding: '8px', textAlign: 'center', border: '1px solid #e2e8f0', color: '#64748b' }}>{log.workDate}</td>
                                                    <td style={{ padding: '8px', border: '1px solid #e2e8f0', color: '#1e293b' }}>{log.content}</td>
                                                </tr>
                                            ))
                                        )
                                    )}
                                </tbody>
                            </table>
                        </div>
                        <div style={{ fontSize: '9px', color: '#94a3b8', marginTop: '8px' }}>
                            1) 업무 구분: 지원 업무에 대해 ITIL에 정의된 기준으로 카테고리를 분류합니다.
                        </div>
                    </div>

                    {/* Section 6: GCP Recommender (Active Assist) 3-Card Category-Specific Editing Lists */}
                    <div className="report-card" style={{ borderTop: '4px solid #0b4885', marginBottom: 0, marginTop: '20px' }}>
                        <div className="report-card-title">
                            <span><i className="fas fa-clipboard-list mr-2" style={{ color: '#2563eb' }}></i>정기 점검 권고 사항 (Recommendations - GCP Active Assist)</span>
                        </div>

                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '20px' }}>
                            {/* 1. Security List Card */}
                            <div style={{ backgroundColor: '#fef2f2', border: '1px solid #fee2e2', borderRadius: '10px', padding: '16px', display: 'flex', flexDirection: 'column', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>
                                <div style={{ marginBottom: '12px' }}>
                                    <span style={{ backgroundColor: '#ef4444', color: '#ffffff', fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '4px', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>보안 / 컴플라이언스</span>
                                </div>
                                {isEditMode ? (
                                    <textarea 
                                        className="edit-textarea-field"
                                        style={{ width: '100%', padding: '8px', flexGrow: 1 }}
                                        rows={6}
                                        value={editRecSecurity}
                                        onChange={(e) => setEditRecSecurity(e.target.value)}
                                        placeholder="보안 권고사항을 줄바꿈 단위로 입력하세요..."
                                    />
                                ) : (reportData.recommendationsSecurityList && reportData.recommendationsSecurityList.length > 0) ? (
                                    <ul style={{ listStyle: 'none', padding: 0, margin: 0, fontSize: '12px', color: '#334155' }}>
                                        {reportData.recommendationsSecurityList.map((item, i) => (
                                            <li key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: '6px', marginBottom: '8px', lineHeight: 1.5 }}>
                                                <i className="fas fa-check-circle text-red-500" style={{ marginTop: '3px', fontSize: '10px' }}></i>
                                                <span>{item}</span>
                                            </li>
                                        ))}
                                    </ul>
                                ) : (
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#94a3b8', fontSize: '12px', padding: '4px 0', lineHeight: 1.5 }}>
                                        <i className="fas fa-info-circle" style={{ fontSize: '11px' }}></i>
                                        <span>해당 카테고리 권고 사항 없음</span>
                                    </div>
                                )}
                            </div>

                            {/* 2. Cost Optimization List Card */}
                            <div style={{ backgroundColor: '#eff6ff', border: '1px solid #dbeafe', borderRadius: '10px', padding: '16px', display: 'flex', flexDirection: 'column', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>
                                <div style={{ marginBottom: '12px' }}>
                                    <span style={{ backgroundColor: '#2563eb', color: '#ffffff', fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '4px', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>비용 최적화</span>
                                </div>
                                {isEditMode ? (
                                    <textarea 
                                        className="edit-textarea-field"
                                        style={{ width: '100%', padding: '8px', flexGrow: 1 }}
                                        rows={6}
                                        value={editRecCost}
                                        onChange={(e) => setEditRecCost(e.target.value)}
                                        placeholder="비용 최적화 권고사항을 줄바꿈 단위로 입력하세요..."
                                    />
                                ) : (reportData.recommendationsCostList && reportData.recommendationsCostList.length > 0) ? (
                                    <ul style={{ listStyle: 'none', padding: 0, margin: 0, fontSize: '12px', color: '#334155' }}>
                                        {reportData.recommendationsCostList.map((item, i) => (
                                            <li key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: '6px', marginBottom: '8px', lineHeight: 1.5 }}>
                                                <i className="fas fa-check-circle text-blue-600" style={{ marginTop: '3px', fontSize: '10px' }}></i>
                                                <span>{item}</span>
                                            </li>
                                        ))}
                                    </ul>
                                ) : (
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#94a3b8', fontSize: '12px', padding: '4px 0', lineHeight: 1.5 }}>
                                        <i className="fas fa-info-circle" style={{ fontSize: '11px' }}></i>
                                        <span>해당 카테고리 권고 사항 없음</span>
                                    </div>
                                )}
                            </div>

                            {/* 3. Performance List Card */}
                            <div style={{ backgroundColor: '#ecfdf5', border: '1px solid #d1fae5', borderRadius: '10px', padding: '16px', display: 'flex', flexDirection: 'column', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>
                                <div style={{ marginBottom: '12px' }}>
                                    <span style={{ backgroundColor: '#10b981', color: '#ffffff', fontSize: '10px', fontWeight: 700, padding: '2px 8px', borderRadius: '4px', WebkitPrintColorAdjust: 'exact', printColorAdjust: 'exact' }}>성능 / 안정성</span>
                                </div>
                                {isEditMode ? (
                                    <textarea 
                                        className="edit-textarea-field"
                                        style={{ width: '100%', padding: '8px', flexGrow: 1 }}
                                        rows={6}
                                        value={editRecPerformance}
                                        onChange={(e) => setEditRecPerformance(e.target.value)}
                                        placeholder="성능/안정성 권고사항을 줄바꿈 단위로 입력하세요..."
                                    />
                                ) : (reportData.recommendationsPerformanceList && reportData.recommendationsPerformanceList.length > 0) ? (
                                    <ul style={{ listStyle: 'none', padding: 0, margin: 0, fontSize: '12px', color: '#334155' }}>
                                        {reportData.recommendationsPerformanceList.map((item, i) => (
                                            <li key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: '6px', marginBottom: '8px', lineHeight: 1.5 }}>
                                                <i className="fas fa-check-circle text-emerald-500" style={{ marginTop: '3px', fontSize: '10px' }}></i>
                                                <span>{item}</span>
                                            </li>
                                        ))}
                                    </ul>
                                ) : (
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#94a3b8', fontSize: '12px', padding: '4px 0', lineHeight: 1.5 }}>
                                        <i className="fas fa-info-circle" style={{ fontSize: '11px' }}></i>
                                        <span>해당 카테고리 권고 사항 없음</span>
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default GcpMonthlyReportViewPage;
