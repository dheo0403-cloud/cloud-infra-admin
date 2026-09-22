import React, { useEffect, useState, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { getCustomers, getUpcomingExpiryReservations, InfraCustomer, ReservationDto } from '../services/api';

const DashboardPage: React.FC = () => {
    const [customers, setCustomers] = useState<InfraCustomer[]>([]);
    const [upcomingReservations, setUpcomingReservations] = useState<ReservationDto[]>([]);
    const [loading, setLoading] = useState<boolean>(true);
    const [activeSummaryTab, setActiveSummaryTab] = useState<'ALL' | 'GCP' | 'AZURE'>('ALL');

    useEffect(() => {
        const fetchData = async () => {
            setLoading(true);
            try {
                const [custRes, resvRes] = await Promise.allSettled([
                    getCustomers(),
                    getUpcomingExpiryReservations()
                ]);

                if (custRes.status === 'fulfilled') {
                    setCustomers(custRes.value.data || []);
                }
                if (resvRes.status === 'fulfilled') {
                    setUpcomingReservations(resvRes.value.data || []);
                }
            } catch (e) {
                console.error("Dashboard data load error:", e);
            } finally {
                setLoading(false);
            }
        };

        fetchData();
    }, []);

    // 1. 전체 기본 메트릭 집계
    const totalCustomers = customers.length;
    let gcpEnvCount = 0;
    let azureEnvCount = 0;

    customers.forEach(c => {
        if (c.environments) {
            c.environments.forEach(env => {
                if (env.providerType === 'GCP') gcpEnvCount++;
                if (env.providerType === 'AZURE') azureEnvCount++;
            });
        }
    });

    const totalEnvs = gcpEnvCount + azureEnvCount;
    const gcpPercent = totalEnvs > 0 ? Math.round((gcpEnvCount / totalEnvs) * 100) : 0;
    const azurePercent = totalEnvs > 0 ? Math.round((azureEnvCount / totalEnvs) * 100) : 0;

    // 2. 글로벌 벤더 필터링 (activeSummaryTab 기준 파생 상태)
    const filteredCustomers = useMemo(() => {
        if (activeSummaryTab === 'ALL') return customers;
        return customers.filter(c =>
            c.environments && c.environments.some(env => env.providerType === activeSummaryTab)
        );
    }, [customers, activeSummaryTab]);

    const filteredReservations = useMemo(() => {
        if (activeSummaryTab === 'ALL') return upcomingReservations;
        return upcomingReservations.filter(res => {
            const providerUpper = (res.provider || '').toUpperCase();
            const typeUpper = (res.type || '').toUpperCase();
            const resNameUpper = (res.reservationName || '').toUpperCase();

            if (activeSummaryTab === 'GCP') {
                return providerUpper === 'GCP' || typeUpper.includes('CUD') || resNameUpper.includes('CUD');
            }
            if (activeSummaryTab === 'AZURE') {
                return providerUpper === 'AZURE' || providerUpper === 'MICROSOFT' || typeUpper.includes('RI') || resNameUpper.includes('RI');
            }
            return true;
        });
    }, [upcomingReservations, activeSummaryTab]);

    // D-Day 계산 헬퍼 함수
    const getDDayInfo = (expiryDateStr: string) => {
        if (!expiryDateStr) return { text: '-', class: 'badge-secondary', days: 999 };
        const expiry = new Date(expiryDateStr);
        const today = new Date();
        const diffTime = expiry.getTime() - today.getTime();
        const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

        if (diffDays < 0) {
            return { text: `만료됨 (${Math.abs(diffDays)}일 전)`, class: 'badge-bd-pink font-weight-bold', days: diffDays };
        }
        if (diffDays <= 7) {
            return { text: `D-${diffDays}`, class: 'badge-danger font-weight-bold', days: diffDays };
        }
        if (diffDays <= 30) {
            return { text: `D-${diffDays}`, class: 'badge-bd-warning font-weight-bold', days: diffDays };
        }
        return { text: `D-${diffDays}`, class: 'badge-bd-teal font-weight-bold', days: diffDays };
    };

    // 필터링된 예약 목록 기준 긴급 만료 건수 집계 (D-7 이내, D-30 이내)
    let urgent7DaysCount = 0;
    let warning30DaysCount = 0;

    filteredReservations.forEach(res => {
        const info = getDDayInfo(res.expiryDate);
        if (info.days <= 7) urgent7DaysCount++;
        else if (info.days <= 30) warning30DaysCount++;
    });

    // 공급자 표기 헬퍼
    const getProviderName = (tab: 'ALL' | 'GCP' | 'AZURE') => {
        if (tab === 'GCP') return 'GCP';
        if (tab === 'AZURE') return 'Azure';
        return '전체';
    };

    // SVG 링 게이지 계산
    const radius = 38;
    const circumference = 2 * Math.PI * radius;
    const displayRingOffset = activeSummaryTab === 'GCP'
        ? 0
        : activeSummaryTab === 'AZURE'
        ? circumference
        : circumference - (circumference * gcpPercent) / 100;

    const displayRingColor = activeSummaryTab === 'GCP' ? '#00f2c3' : (activeSummaryTab === 'AZURE' ? '#1d8cf8' : '#00f2c3');
    const displayBgRingColor = activeSummaryTab === 'AZURE' ? '#1d8cf8' : 'rgba(29, 140, 248, 0.2)';

    return (
        <div className="container-fluid px-0">
            {/* 1. System Overview: Multi-Cloud Interactive Visualizer Card */}
            <div className="card mb-4" style={{ background: 'linear-gradient(145deg, #27293d 0%, #1e1e2f 100%)', border: '1px solid rgba(255,255,255,0.06)' }}>
                <div className="card-header border-0 d-flex flex-wrap justify-content-between align-items-center pb-2">
                    <div>
                        <div className="d-flex align-items-center">
                            <span className="badge badge-bd-blue px-2 py-1 mr-2" style={{ fontSize: '0.7rem' }}>
                                {activeSummaryTab === 'ALL' ? 'Global Overview' : `${activeSummaryTab} Focus View`}
                            </span>
                            <h3 className="card-title text-white font-weight-400 m-0" style={{ fontSize: '1.25rem' }}>
                                <i className={`fas ${activeSummaryTab === 'ALL' ? 'fa-layer-group text-primary' : (activeSummaryTab === 'GCP' ? 'fab fa-google text-teal' : 'fab fa-microsoft text-info')} mr-2`}></i>
                                {activeSummaryTab === 'ALL' && '전체 클라우드 인프라 자원 통합 현황'}
                                {activeSummaryTab === 'GCP' && 'GCP (Google Cloud) 프로젝트 및 자원 집중 관제'}
                                {activeSummaryTab === 'AZURE' && 'Azure 구독 및 자원 집중 관제'}
                            </h3>
                        </div>
                        <p className="text-muted small mt-1 mb-0">
                            {activeSummaryTab === 'ALL' && '멀티 클라우드 환경의 실시간 프로젝트 분포 및 인프라 상태를 모니터링합니다.'}
                            {activeSummaryTab === 'GCP' && 'Google Cloud Platform 환경의 고객사, 프로젝트 자원 및 CUD 약정 현황을 필터링하여 모니터링합니다.'}
                            {activeSummaryTab === 'AZURE' && 'Microsoft Azure 환경의 고객사, 구독 자원 및 RI 약정 현황을 필터링하여 모니터링합니다.'}
                        </p>
                    </div>
                    <div className="d-flex align-items-center mt-3 mt-md-0">
                        {/* Tab Toggle Buttons */}
                        <div className="btn-group btn-group-toggle mr-3">
                            <button
                                className={`btn btn-sm ${activeSummaryTab === 'ALL' ? 'btn-blue' : 'btn-outline-secondary text-light'}`}
                                onClick={() => setActiveSummaryTab('ALL')}
                                style={{ fontSize: '0.8rem', padding: '6px 14px' }}
                            >
                                전체 요약 ({totalCustomers})
                            </button>
                            <button
                                className={`btn btn-sm ${activeSummaryTab === 'GCP' ? 'btn-blue' : 'btn-outline-secondary text-light'}`}
                                onClick={() => setActiveSummaryTab('GCP')}
                                style={{ fontSize: '0.8rem', padding: '6px 14px' }}
                            >
                                <i className="fab fa-google mr-1"></i>GCP ({gcpEnvCount})
                            </button>
                            <button
                                className={`btn btn-sm ${activeSummaryTab === 'AZURE' ? 'btn-blue' : 'btn-outline-secondary text-light'}`}
                                onClick={() => setActiveSummaryTab('AZURE')}
                                style={{ fontSize: '0.8rem', padding: '6px 14px' }}
                            >
                                <i className="fab fa-microsoft mr-1"></i>Azure ({azureEnvCount})
                            </button>
                        </div>
                        {/* New Customer / Env Registration Action Button */}
                        <Link to="/management/register" className="btn btn-blue btn-sm" style={{ padding: '6px 14px', fontSize: '0.8rem' }}>
                            <i className="fas fa-plus mr-1"></i>고객사 및 환경 등록
                        </Link>
                    </div>
                </div>

                <div className="card-body pt-3">
                    <div className="row align-items-center">
                        {/* Left: SVG Ring Gauge & Multi-Cloud Stats */}
                        <div className="col-lg-8 col-md-7 mb-3 mb-md-0">
                            <div className="d-flex flex-wrap align-items-center">
                                {/* SVG Ring Gauge Visualizer */}
                                <div className="position-relative d-flex align-items-center justify-content-center mr-4 my-2" style={{ width: '96px', height: '96px' }}>
                                    <svg width="96" height="96" viewBox="0 0 96 96" className="transform -rotate-90" style={{ transform: 'rotate(-90deg)' }}>
                                        <circle
                                            cx="48"
                                            cy="48"
                                            r={radius}
                                            stroke={displayBgRingColor}
                                            strokeWidth="9"
                                            fill="transparent"
                                        />
                                        <circle
                                            cx="48"
                                            cy="48"
                                            r={radius}
                                            stroke={displayRingColor}
                                            strokeWidth="9"
                                            strokeDasharray={circumference}
                                            strokeDashoffset={displayRingOffset}
                                            strokeLinecap="round"
                                            fill="transparent"
                                            style={{ transition: 'stroke-dashoffset 0.6s ease, stroke 0.6s ease' }}
                                        />
                                    </svg>
                                    <div className="position-absolute text-center" style={{ pointerEvents: 'none' }}>
                                        <div className="text-white font-weight-bold" style={{ fontSize: '1.05rem', lineHeight: 1.1 }}>
                                            {activeSummaryTab === 'ALL' ? totalEnvs : (activeSummaryTab === 'GCP' ? gcpEnvCount : azureEnvCount)}
                                        </div>
                                        <div className="text-muted" style={{ fontSize: '0.65rem' }}>
                                            {activeSummaryTab === 'ALL' ? 'Environments' : (activeSummaryTab === 'GCP' ? 'GCP Projects' : 'Azure Subs')}
                                        </div>
                                    </div>
                                </div>

                                {/* Progress Bars & CSP Details */}
                                <div className="flex-grow-1" style={{ minWidth: '220px' }}>
                                    <div className="d-flex justify-content-between align-items-center mb-2 small font-weight-600">
                                        <span className={`d-flex align-items-center ${activeSummaryTab === 'AZURE' ? 'opacity-50' : ''}`} style={{ opacity: activeSummaryTab === 'AZURE' ? 0.4 : 1, transition: 'opacity 0.3s' }}>
                                            <i className="fab fa-google text-teal mr-2" style={{ color: '#00f2c3' }}></i>
                                            <span className="text-white">GCP 인프라</span>
                                            <span className="badge badge-bd-teal ml-2">{gcpEnvCount}개 프로젝트 ({gcpPercent}%)</span>
                                        </span>
                                        <span className={`d-flex align-items-center ${activeSummaryTab === 'GCP' ? 'opacity-50' : ''}`} style={{ opacity: activeSummaryTab === 'GCP' ? 0.4 : 1, transition: 'opacity 0.3s' }}>
                                            <i className="fab fa-microsoft text-info mr-2" style={{ color: '#1d8cf8' }}></i>
                                            <span className="text-white">Azure 인프라</span>
                                            <span className="badge badge-bd-azure ml-2">{azureEnvCount}개 구독 ({azurePercent}%)</span>
                                        </span>
                                    </div>

                                    {/* Multi-Segment Gradient Bar */}
                                    <div className="progress" style={{ height: '14px', backgroundColor: '#1d1e2c', borderRadius: '10px', overflow: 'hidden' }}>
                                        <div
                                            className="progress-bar"
                                            role="progressbar"
                                            style={{
                                                width: activeSummaryTab === 'AZURE' ? '0%' : (activeSummaryTab === 'GCP' ? '100%' : `${gcpPercent}%`),
                                                background: 'linear-gradient(90deg, #00f2c3, #00d2d3)',
                                                boxShadow: '0 0 10px rgba(0, 242, 195, 0.4)',
                                                transition: 'width 0.5s ease'
                                            }}
                                            title={`GCP: ${gcpPercent}%`}
                                        >
                                            {activeSummaryTab === 'GCP' ? `GCP 100% (${gcpEnvCount}개)` : (gcpPercent > 15 ? `${gcpPercent}%` : '')}
                                        </div>
                                        <div
                                            className="progress-bar"
                                            role="progressbar"
                                            style={{
                                                width: activeSummaryTab === 'GCP' ? '0%' : (activeSummaryTab === 'AZURE' ? '100%' : `${azurePercent}%`),
                                                background: 'linear-gradient(90deg, #1d8cf8, #3358f4)',
                                                boxShadow: '0 0 10px rgba(29, 140, 248, 0.4)',
                                                transition: 'width 0.5s ease'
                                            }}
                                            title={`Azure: ${azurePercent}%`}
                                        >
                                            {activeSummaryTab === 'AZURE' ? `Azure 100% (${azureEnvCount}개)` : (azurePercent > 15 ? `${azurePercent}%` : '')}
                                        </div>
                                    </div>

                                    <div className="d-flex justify-content-between text-muted small mt-2">
                                        <span style={{ color: activeSummaryTab === 'GCP' ? '#00f2c3' : '#9a9a9a', fontWeight: activeSummaryTab === 'GCP' ? 700 : 400 }}>
                                            <i className="fas fa-circle mr-1" style={{ color: '#00f2c3', fontSize: '0.6rem' }}></i>Google Cloud Platform
                                        </span>
                                        <span style={{ color: activeSummaryTab === 'AZURE' ? '#1d8cf8' : '#9a9a9a', fontWeight: activeSummaryTab === 'AZURE' ? 700 : 400 }}>
                                            <i className="fas fa-circle mr-1" style={{ color: '#1d8cf8', fontSize: '0.6rem' }}></i>Microsoft Azure
                                        </span>
                                    </div>
                                </div>
                            </div>
                        </div>

                        {/* Right: Engine Status & Synchronization Info */}
                        <div className="col-lg-4 col-md-5 text-md-right border-left border-secondary pl-md-4">
                            <div className="card-category text-muted" style={{ letterSpacing: '1px' }}>시스템 실시간 상태</div>
                            <div className="d-flex align-items-center justify-content-md-end mt-2">
                                <span className="badge badge-success px-2 py-1 mr-2" style={{ backgroundColor: 'rgba(0, 242, 195, 0.15)', color: '#00f2c3', border: '1px solid rgba(0, 242, 195, 0.3)' }}>
                                    <i className="fas fa-heartbeat mr-1"></i>Healthy
                                </span>
                                <span className="text-white font-weight-bold" style={{ fontSize: '0.92rem' }}>
                                    {activeSummaryTab === 'ALL' && '멀티 테넌트 배치 & 수집 정상'}
                                    {activeSummaryTab === 'GCP' && 'GCP Monitoring & BigQuery 정상'}
                                    {activeSummaryTab === 'AZURE' && 'Azure Resource Graph 수집 정상'}
                                </span>
                            </div>
                            <div className="text-muted small mt-2">
                                <i className="fas fa-sync-alt fa-spin mr-1" style={{ animationDuration: '4s' }}></i>
                                필터 뷰: <strong className="text-light">{activeSummaryTab === 'ALL' ? '전체 통합' : activeSummaryTab}</strong> ({new Date().toLocaleDateString('ko-KR')})
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* 2. 4 Color-coded High-Impact Stat Cards */}
            <div className="row">
                {/* Total Customers Card */}
                <div className="col-lg-3 col-md-6 col-sm-6 mb-4">
                    <div className="card p-3 h-100" style={{ borderLeft: '3px solid #1d8cf8' }}>
                        <div className="d-flex justify-content-between align-items-center">
                            <div>
                                <div className="card-category">
                                    {activeSummaryTab === 'ALL' ? 'Total Customers' : `${getProviderName(activeSummaryTab)} Customers`}
                                </div>
                                <h2 className="text-white font-weight-600 m-0" style={{ fontSize: '1.8rem' }}>
                                    {loading ? '...' : filteredCustomers.length}
                                    <span className="small font-weight-normal text-muted ml-1" style={{ fontSize: '0.9rem' }}>개사</span>
                                </h2>
                            </div>
                            <div className="p-3 rounded" style={{ background: 'rgba(29, 140, 248, 0.15)', color: '#1d8cf8' }}>
                                <i className="fas fa-building fa-2x"></i>
                            </div>
                        </div>
                        <div className="mt-3 pt-2 border-top border-secondary d-flex justify-content-between align-items-center">
                            <Link to="/management" className="text-decoration-none small font-weight-600" style={{ color: '#1d8cf8' }}>
                                고객사 관리 <i className="fas fa-arrow-right ml-1"></i>
                            </Link>
                            <span className="badge badge-bd-blue">
                                {activeSummaryTab === 'ALL' ? 'All Managed' : `${activeSummaryTab} Active`}
                            </span>
                        </div>
                    </div>
                </div>

                {/* GCP Projects Card */}
                <div className="col-lg-3 col-md-6 col-sm-6 mb-4">
                    <div className="card p-3 h-100" style={{
                        borderLeft: '3px solid #00f2c3',
                        boxShadow: activeSummaryTab === 'GCP' ? '0 0 15px rgba(0, 242, 195, 0.25)' : 'none',
                        transition: 'box-shadow 0.3s ease'
                    }}>
                        <div className="d-flex justify-content-between align-items-center">
                            <div>
                                <div className="card-category">GCP Projects</div>
                                <h2 className="text-white font-weight-600 m-0" style={{ fontSize: '1.8rem' }}>
                                    {loading ? '...' : gcpEnvCount}
                                    <span className="small font-weight-normal text-muted ml-1" style={{ fontSize: '0.9rem' }}>개 프로젝트</span>
                                </h2>
                            </div>
                            <div className="p-3 rounded" style={{ background: 'rgba(0, 242, 195, 0.15)', color: '#00f2c3' }}>
                                <i className="fab fa-google fa-2x"></i>
                            </div>
                        </div>
                        <div className="mt-3 pt-2 border-top border-secondary d-flex justify-content-between align-items-center">
                            <Link to="/gcp-report" className="text-decoration-none small font-weight-600" style={{ color: '#00f2c3' }}>
                                점검 및 보고서 <i className="fas fa-arrow-right ml-1"></i>
                            </Link>
                            <span className={`badge ${activeSummaryTab === 'GCP' ? 'badge-success font-weight-bold' : 'badge-bd-teal'}`}>
                                {activeSummaryTab === 'GCP' ? 'Focus Active' : `${gcpPercent}% 점유율`}
                            </span>
                        </div>
                    </div>
                </div>

                {/* Azure Subscriptions Card */}
                <div className="col-lg-3 col-md-6 col-sm-6 mb-4">
                    <div className="card p-3 h-100" style={{
                        borderLeft: '3px solid #3358f4',
                        boxShadow: activeSummaryTab === 'AZURE' ? '0 0 15px rgba(51, 88, 244, 0.3)' : 'none',
                        transition: 'box-shadow 0.3s ease'
                    }}>
                        <div className="d-flex justify-content-between align-items-center">
                            <div>
                                <div className="card-category">Azure Subscriptions</div>
                                <h2 className="text-white font-weight-600 m-0" style={{ fontSize: '1.8rem' }}>
                                    {loading ? '...' : azureEnvCount}
                                    <span className="small font-weight-normal text-muted ml-1" style={{ fontSize: '0.9rem' }}>개 구독</span>
                                </h2>
                            </div>
                            <div className="p-3 rounded" style={{ background: 'rgba(51, 88, 244, 0.15)', color: '#3358f4' }}>
                                <i className="fab fa-microsoft fa-2x"></i>
                            </div>
                        </div>
                        <div className="mt-3 pt-2 border-top border-secondary d-flex justify-content-between align-items-center">
                            <Link to="/azure-report" className="text-decoration-none small font-weight-600" style={{ color: '#1d8cf8' }}>
                                점검 및 보고서 <i className="fas fa-arrow-right ml-1"></i>
                            </Link>
                            <span className={`badge ${activeSummaryTab === 'AZURE' ? 'badge-primary font-weight-bold' : 'badge-bd-azure'}`}>
                                {activeSummaryTab === 'AZURE' ? 'Focus Active' : `${azurePercent}% 점유율`}
                            </span>
                        </div>
                    </div>
                </div>

                {/* Expiring Reservations Card (CUD / RI) */}
                <div className="col-lg-3 col-md-6 col-sm-6 mb-4">
                    <div className="card p-3 h-100" style={{ borderLeft: '3px solid #ff3860' }}>
                        <div className="d-flex justify-content-between align-items-center">
                            <div>
                                <div className="card-category">
                                    {activeSummaryTab === 'ALL' ? 'Expiring Reservations' : `${getProviderName(activeSummaryTab)} ${activeSummaryTab === 'GCP' ? 'CUD' : 'RI'} Expiry`}
                                </div>
                                <h2 className="text-white font-weight-600 m-0" style={{ fontSize: '1.8rem' }}>
                                    {loading ? '...' : filteredReservations.length}
                                    <span className="small font-weight-normal text-muted ml-1" style={{ fontSize: '0.9rem' }}>건</span>
                                </h2>
                            </div>
                            <div className="p-3 rounded" style={{ background: 'rgba(255, 56, 96, 0.15)', color: '#ff3860' }}>
                                <i className="fas fa-exclamation-triangle fa-2x"></i>
                            </div>
                        </div>
                        <div className="mt-3 pt-2 border-top border-secondary d-flex justify-content-between align-items-center">
                            <Link to="/reservations" className="text-decoration-none small font-weight-600" style={{ color: '#ff3860' }}>
                                {activeSummaryTab === 'ALL' ? 'CUD/RI 예약 관리' : (activeSummaryTab === 'GCP' ? 'CUD 예약 관리' : 'RI 예약 관리')} <i className="fas fa-arrow-right ml-1"></i>
                            </Link>
                            <div>
                                {urgent7DaysCount > 0 ? (
                                    <span className="badge badge-danger mr-1" style={{ backgroundColor: '#ff3860' }}>
                                        🚨 7일내 {urgent7DaysCount}건
                                    </span>
                                ) : null}
                                {warning30DaysCount > 0 ? (
                                    <span className="badge badge-warning" style={{ backgroundColor: '#ff8d72', color: '#1e1e2f' }}>
                                        ⚠️ 30일내 {warning30DaysCount}건
                                    </span>
                                ) : (
                                    <span className="badge badge-bd-teal">안정</span>
                                )}
                            </div>
                        </div>
                    </div>
                </div>
            </div>

            {/* 3. Bottom 3-Column Intelligent Grid */}
            <div className="row">
                {/* Left Card: Customer Overview (Filtered Top 5) */}
                <div className="col-lg-4 mb-4">
                    <div className="card h-100">
                        <div className="card-header d-flex justify-content-between align-items-center">
                            <div>
                                <h5 className="card-category">Management</h5>
                                <h4 className="card-title text-white">
                                    <i className="fas fa-users text-info mr-2"></i>
                                    {activeSummaryTab === 'ALL' ? '등록 고객사' : `${getProviderName(activeSummaryTab)} 등록 고객사`}
                                    <span className="badge badge-bd-blue ml-2" style={{ fontSize: '0.75rem' }}>{filteredCustomers.length}</span>
                                </h4>
                            </div>
                            <Link to="/management" className="btn btn-sm btn-outline-secondary text-light">전체보기</Link>
                        </div>
                        <div className="card-body p-0">
                            {loading ? (
                                <div className="text-center py-4 text-muted">
                                    <i className="fas fa-spinner fa-spin mr-2"></i>불러오는 중...
                                </div>
                            ) : filteredCustomers.length === 0 ? (
                                <div className="text-center py-5 text-muted small">
                                    <i className="fas fa-info-circle mr-1"></i>선택된 {getProviderName(activeSummaryTab)} 환경을 사용하는 고객사가 없습니다.
                                </div>
                            ) : (
                                <div className="table-responsive">
                                    <table className="table mb-0">
                                        <thead>
                                            <tr>
                                                <th>고객사명</th>
                                                <th>담당자</th>
                                                <th>환경</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {filteredCustomers.slice(0, 5).map((cust) => (
                                                <tr key={cust.id}>
                                                    <td><strong className="text-white">{cust.name}</strong></td>
                                                    <td><span style={{ color: '#cbd5e1' }}>{cust.contactPerson || '-'}</span></td>
                                                    <td>
                                                        {cust.environments && cust.environments.length > 0 ? (
                                                            cust.environments
                                                                .filter(env => activeSummaryTab === 'ALL' || env.providerType === activeSummaryTab)
                                                                .map((env, idx) => (
                                                                    <span key={idx} className={`badge ${env.providerType === 'GCP' ? 'badge-bd-gcp' : 'badge-bd-azure'} mr-1`}>
                                                                        {env.providerType}
                                                                    </span>
                                                                ))
                                                        ) : (
                                                            <span className="text-muted small">-</span>
                                                        )}
                                                    </td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Center Card: Quick Automation Actions (Context-Aware) */}
                <div className="col-lg-4 mb-4">
                    <div className="card h-100">
                        <div className="card-header">
                            <h5 className="card-category">Quick Tasks</h5>
                            <h4 className="card-title text-white">
                                <i className="fas fa-bolt text-warning mr-2"></i>
                                {activeSummaryTab === 'ALL' ? '자동화 작업 수행' : `${getProviderName(activeSummaryTab)} 자동화 작업`}
                            </h4>
                        </div>
                        <div className="card-body d-flex flex-column justify-content-between p-3">
                            {/* Action 1: GCP Monthly Report */}
                            {(activeSummaryTab === 'ALL' || activeSummaryTab === 'GCP') && (
                                <div className="p-3 mb-2 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.05)' }}>
                                    <div className="d-flex align-items-center justify-content-between mb-1">
                                        <div className="d-flex align-items-center">
                                            <i className="fab fa-google text-danger fa-lg mr-2"></i>
                                            <strong className="text-white" style={{ fontSize: '0.9rem' }}>GCP 인프라 월간 보고서</strong>
                                        </div>
                                        <Link to="/gcp-report" className="btn btn-blue btn-sm" style={{ padding: '3px 10px', fontSize: '0.75rem' }}>바로가기</Link>
                                    </div>
                                    <p className="text-muted small mb-0">VM, Direct AI, BQ 최적화 지표를 웹/PDF 보고서로 생성합니다.</p>
                                </div>
                            )}

                            {/* Action 2: Azure Checklist / Report */}
                            {(activeSummaryTab === 'ALL' || activeSummaryTab === 'AZURE') && (
                                <div className="p-3 mb-2 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.05)' }}>
                                    <div className="d-flex align-items-center justify-content-between mb-1">
                                        <div className="d-flex align-items-center">
                                            <i className="fab fa-microsoft text-info fa-lg mr-2"></i>
                                            <strong className="text-white" style={{ fontSize: '0.9rem' }}>Azure 온보딩 점검표</strong>
                                        </div>
                                        <Link to="/azure-checklist" className="btn btn-blue btn-sm" style={{ padding: '3px 10px', fontSize: '0.75rem' }}>바로가기</Link>
                                    </div>
                                    <p className="text-muted small mb-0">Azure 구독 내 VM 인스턴스, 보안 및 자원 점검표를 생성합니다.</p>
                                </div>
                            )}

                            {/* Action 3: CUD / RI Reservation Manager */}
                            <div className="p-3 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.05)' }}>
                                <div className="d-flex align-items-center justify-content-between mb-1">
                                    <div className="d-flex align-items-center">
                                        <i className="fas fa-calendar-alt text-warning fa-lg mr-2"></i>
                                        <strong className="text-white" style={{ fontSize: '0.9rem' }}>
                                            {activeSummaryTab === 'ALL' ? 'CUD / RI 약정 예약 관리' : (activeSummaryTab === 'GCP' ? 'GCP CUD 약정 관리' : 'Azure RI 약정 관리')}
                                        </strong>
                                    </div>
                                    <Link to="/reservations" className="btn btn-blue btn-sm" style={{ padding: '3px 10px', fontSize: '0.75rem' }}>바로가기</Link>
                                </div>
                                <p className="text-muted small mb-0">
                                    {activeSummaryTab === 'ALL' ? '약정 만료 임박 모니터링 및 신규 약정 예약을 등록·관리합니다.' : (activeSummaryTab === 'GCP' ? 'GCP Compute & SQL 확정 사용 할인(CUD)을 관리합니다.' : 'Azure 예약 인스턴스(RI) 약정을 관리합니다.')}
                                </p>
                            </div>
                        </div>
                    </div>
                </div>

                {/* Right Card: Upcoming Expirations Table (Filtered Top 5) */}
                <div className="col-lg-4 mb-4">
                    <div className="card h-100">
                        <div className="card-header d-flex justify-content-between align-items-center">
                            <div>
                                <h5 className="card-category">Notifications</h5>
                                <h4 className="card-title text-white">
                                    <i className="fas fa-clock text-warning mr-2"></i>
                                    {activeSummaryTab === 'ALL' ? '만료 예정 예약' : `${getProviderName(activeSummaryTab)} 만료 예정 약정`}
                                    <span className="badge badge-bd-red ml-2" style={{ fontSize: '0.75rem' }}>{filteredReservations.length}</span>
                                </h4>
                            </div>
                            <Link to="/reservations" className="btn btn-sm btn-outline-secondary text-light">전체보기</Link>
                        </div>
                        <div className="card-body p-0">
                            {loading ? (
                                <div className="text-center py-4 text-muted">
                                    <i className="fas fa-spinner fa-spin mr-2"></i>조회 중...
                                </div>
                            ) : filteredReservations.length === 0 ? (
                                <div className="text-center py-5 text-muted small">
                                    <i className="fas fa-check-circle text-success mr-1"></i>선택된 {getProviderName(activeSummaryTab)} 벤더의 만료 예정 약정이 없습니다.
                                </div>
                            ) : (
                                <div className="table-responsive">
                                    <table className="table mb-0">
                                        <thead>
                                            <tr>
                                                <th>고객사</th>
                                                <th>예약명</th>
                                                <th>D-Day</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {filteredReservations.slice(0, 5).map((res, i) => {
                                                const dday = getDDayInfo(res.expiryDate);
                                                return (
                                                    <tr key={i}>
                                                        <td><strong className="text-white">{res.customerName}</strong></td>
                                                        <td>
                                                            <span className="text-light" title={res.reservationName}>
                                                                {res.reservationName}
                                                            </span>
                                                        </td>
                                                        <td>
                                                            <span className={`badge ${dday.class}`}>
                                                                {dday.text}
                                                            </span>
                                                        </td>
                                                    </tr>
                                                );
                                            })}
                                        </tbody>
                                    </table>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default DashboardPage;
