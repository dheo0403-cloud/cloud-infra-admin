import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getCustomers, getUpcomingExpiryReservations, InfraCustomer, ReservationDto } from '../services/api';
import VertexAiOperationsPanel from '../components/VertexAiOperationsPanel';

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

    // Calculate metrics
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
    const upcomingCount = upcomingReservations.length;

    // Helper to calculate D-Day
    const getDDay = (expiryDateStr: string) => {
        if (!expiryDateStr) return { text: '-', class: 'badge-secondary' };
        const expiry = new Date(expiryDateStr);
        const today = new Date();
        const diffTime = expiry.getTime() - today.getTime();
        const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));

        if (diffDays < 0) return { text: `만료됨 (${Math.abs(diffDays)}일 전)`, class: 'badge-bd-pink' };
        if (diffDays <= 7) return { text: `D-${diffDays}`, class: 'badge-bd-warning font-weight-bold' };
        if (diffDays <= 30) return { text: `D-${diffDays}`, class: 'badge-bd-warning' };
        return { text: `D-${diffDays}`, class: 'badge-bd-teal' };
    };

    return (
        <div className="container-fluid">
            {/* Top Page Header */}
            <div className="d-flex justify-content-between align-items-center mb-4">
                <div>
                    <h5 className="card-category m-0" style={{ letterSpacing: '1px' }}>MegazoneCloud Operations</h5>
                    <h2 className="text-white font-weight-300 m-0" style={{ fontFamily: 'Poppins, sans-serif' }}>
                        <i className="fas fa-atom text-primary mr-2"></i>Cloud Infra Admin Dashboard
                    </h2>
                </div>
                <div>
                    <Link to="/management/register" className="btn btn-blue">
                        <i className="fas fa-plus mr-2"></i>신규 고객사 및 환경 등록
                    </Link>
                </div>
            </div>

            {/* 1. Black Dashboard React Signature Big Summary Chart Card */}
            <div className="card mb-4">
                <div className="card-header border-0 d-flex flex-wrap justify-content-between align-items-center">
                    <div>
                        <h5 className="card-category">System Overview</h5>
                        <h3 className="card-title text-white font-weight-300">
                            <i className="fas fa-chart-line text-info mr-2"></i>
                            {activeSummaryTab === 'ALL' && '전체 클라우드 인프라 자원 통합 현황'}
                            {activeSummaryTab === 'GCP' && 'GCP (Google Cloud) 프로젝트 및 자원 점검 현황'}
                            {activeSummaryTab === 'AZURE' && 'Azure 구독 및 자원 점검 현황'}
                        </h3>
                    </div>
                    <div className="btn-group btn-group-toggle mt-2 mt-sm-0" data-toggle="buttons">
                        <button 
                            className={`btn btn-sm ${activeSummaryTab === 'ALL' ? 'btn-blue' : 'btn-outline-secondary text-light'}`}
                            onClick={() => setActiveSummaryTab('ALL')}
                        >
                            전체 요약
                        </button>
                        <button 
                            className={`btn btn-sm ${activeSummaryTab === 'GCP' ? 'btn-blue' : 'btn-outline-secondary text-light'}`}
                            onClick={() => setActiveSummaryTab('GCP')}
                        >
                            GCP ({gcpEnvCount})
                        </button>
                        <button 
                            className={`btn btn-sm ${activeSummaryTab === 'AZURE' ? 'btn-blue' : 'btn-outline-secondary text-light'}`}
                            onClick={() => setActiveSummaryTab('AZURE')}
                        >
                            Azure ({azureEnvCount})
                        </button>
                    </div>
                </div>
                <div className="card-body">
                    <div className="row align-items-center">
                        <div className="col-md-8 mb-3 mb-md-0">
                            <div className="d-flex justify-content-between mb-2 small font-weight-600">
                                <span><i className="fab fa-google text-danger mr-1"></i>GCP 인프라 ({gcpEnvCount}개 환경 / {gcpPercent}%)</span>
                                <span><i className="fab fa-microsoft text-info mr-1"></i>Azure 인프라 ({azureEnvCount}개 환경 / {azurePercent}%)</span>
                            </div>
                            <div className="progress" style={{ height: '18px', backgroundColor: '#1d1e2c', borderRadius: '0.2857rem' }}>
                                <div 
                                    className="progress-bar" 
                                    role="progressbar" 
                                    style={{ width: `${gcpPercent}%`, background: 'linear-gradient(90deg, #1d8cf8, #00f2c3)' }}
                                >
                                    {gcpPercent > 0 ? `${gcpPercent}%` : ''}
                                </div>
                                <div 
                                    className="progress-bar" 
                                    role="progressbar" 
                                    style={{ width: `${azurePercent}%`, background: 'linear-gradient(90deg, #3358f4, #1d8cf8)' }}
                                >
                                    {azurePercent > 0 ? `${azurePercent}%` : ''}
                                </div>
                            </div>
                        </div>
                        <div className="col-md-4 text-md-right border-left border-secondary pl-md-4">
                            <div className="card-category">실시간 상태</div>
                            <div className="d-flex align-items-center justify-content-md-end mt-1">
                                <i className="fas fa-check-circle text-success fa-lg mr-2"></i>
                                <span className="text-white font-weight-bold">배치 엔진 및 수집 API 정상 가동</span>
                            </div>
                            <small className="text-muted d-block mt-1">마지막 동기화: {new Date().toLocaleDateString('ko-KR')} 최신</small>
                        </div>
                    </div>
                </div>
            </div>

            {/* 2. 4 Color-coded Stat Cards (Black Dashboard Style) */}
            <div className="row">
                <div className="col-lg-3 col-md-6 col-sm-6 mb-4">
                    <div className="card p-3 h-100">
                        <div className="d-flex justify-content-between align-items-center">
                            <div>
                                <div className="card-category">Total Customers</div>
                                <h2 className="text-white font-weight-600 m-0">{loading ? '...' : totalCustomers}</h2>
                            </div>
                            <div className="p-3 rounded" style={{ background: 'rgba(29, 140, 248, 0.15)', color: '#1d8cf8' }}>
                                <i className="fas fa-building fa-2x"></i>
                            </div>
                        </div>
                        <div className="mt-3 pt-2 border-top border-secondary d-flex justify-content-between align-items-center">
                            <Link to="/management" className="text-decoration-none small font-weight-600" style={{ color: '#1d8cf8' }}>
                                고객사 관리 <i className="fas fa-arrow-right ml-1"></i>
                            </Link>
                            <span className="badge badge-bd-blue">Active</span>
                        </div>
                    </div>
                </div>

                <div className="col-lg-3 col-md-6 col-sm-6 mb-4">
                    <div className="card p-3 h-100">
                        <div className="d-flex justify-content-between align-items-center">
                            <div>
                                <div className="card-category">GCP Projects</div>
                                <h2 className="text-white font-weight-600 m-0">{loading ? '...' : gcpEnvCount}</h2>
                            </div>
                            <div className="p-3 rounded" style={{ background: 'rgba(0, 242, 195, 0.15)', color: '#00f2c3' }}>
                                <i className="fab fa-google fa-2x"></i>
                            </div>
                        </div>
                        <div className="mt-3 pt-2 border-top border-secondary d-flex justify-content-between align-items-center">
                            <Link to="/gcp-report" className="text-decoration-none small font-weight-600" style={{ color: '#00f2c3' }}>
                                점검 및 보고서 <i className="fas fa-arrow-right ml-1"></i>
                            </Link>
                            <span className="badge badge-bd-teal">Google Cloud</span>
                        </div>
                    </div>
                </div>

                <div className="col-lg-3 col-md-6 col-sm-6 mb-4">
                    <div className="card p-3 h-100">
                        <div className="d-flex justify-content-between align-items-center">
                            <div>
                                <div className="card-category">Azure Subscriptions</div>
                                <h2 className="text-white font-weight-600 m-0">{loading ? '...' : azureEnvCount}</h2>
                            </div>
                            <div className="p-3 rounded" style={{ background: 'rgba(29, 140, 248, 0.15)', color: '#1d8cf8' }}>
                                <i className="fab fa-microsoft fa-2x"></i>
                            </div>
                        </div>
                        <div className="mt-3 pt-2 border-top border-secondary d-flex justify-content-between align-items-center">
                            <Link to="/azure-report" className="text-decoration-none small font-weight-600" style={{ color: '#1d8cf8' }}>
                                점검 및 보고서 <i className="fas fa-arrow-right ml-1"></i>
                            </Link>
                            <span className="badge badge-bd-blue">Azure Cloud</span>
                        </div>
                    </div>
                </div>

                <div className="col-lg-3 col-md-6 col-sm-6 mb-4">
                    <div className="card p-3 h-100">
                        <div className="d-flex justify-content-between align-items-center">
                            <div>
                                <div className="card-category">Expiring Reservations</div>
                                <h2 className="text-white font-weight-600 m-0">{loading ? '...' : upcomingCount}</h2>
                            </div>
                            <div className="p-3 rounded" style={{ background: 'rgba(255, 56, 96, 0.15)', color: '#ff3860' }}>
                                <i className="fas fa-exclamation-triangle fa-2x"></i>
                            </div>
                        </div>
                        <div className="mt-3 pt-2 border-top border-secondary d-flex justify-content-between align-items-center">
                            <Link to="/reservations" className="text-decoration-none small font-weight-600" style={{ color: '#ff3860' }}>
                                CUD/RI 예약 관리 <i className="fas fa-arrow-right ml-1"></i>
                            </Link>
                            <span className="badge badge-danger font-weight-bold" style={{ backgroundColor: '#ff3860', color: '#ffffff' }}>100일 미만</span>
                        </div>
                    </div>
                </div>
            </div>

            {/* 3. Bottom 3 Grid Cards */}
            <div className="row">
                {/* Left Card: Customer Overview */}
                <div className="col-lg-4 mb-4">
                    <div className="card h-100">
                        <div className="card-header d-flex justify-content-between align-items-center">
                            <div>
                                <h5 className="card-category">Management</h5>
                                <h4 className="card-title"><i className="fas fa-users text-info mr-2"></i>등록 고객사</h4>
                            </div>
                            <Link to="/management" className="btn btn-sm btn-outline-secondary text-light">전체보기</Link>
                        </div>
                        <div className="card-body p-0">
                            {loading ? (
                                <div className="text-center py-4 text-muted">
                                    <i className="fas fa-spinner fa-spin mr-2"></i>불러오는 중...
                                </div>
                            ) : customers.length === 0 ? (
                                <div className="text-center py-4 text-muted">등록된 고객사가 없습니다.</div>
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
                                            {customers.slice(0, 5).map((cust) => (
                                                <tr key={cust.id}>
                                                    <td><strong className="text-white">{cust.name}</strong></td>
                                                    <td><span style={{ color: '#cbd5e1' }}>{cust.contactPerson || '-'}</span></td>
                                                    <td>
                                                        {cust.environments && cust.environments.map((env, idx) => (
                                                            <span key={idx} className={`badge ${env.providerType === 'GCP' ? 'badge-bd-gcp' : 'badge-bd-azure'} mr-1`}>
                                                                {env.providerType}
                                                            </span>
                                                        ))}
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

                {/* Center Card: Quick Automation Actions */}
                <div className="col-lg-4 mb-4">
                    <div className="card h-100">
                        <div className="card-header">
                            <h5 className="card-category">Quick Tasks</h5>
                            <h4 className="card-title"><i className="fas fa-bolt text-warning mr-2"></i>자동화 작업 수행</h4>
                        </div>
                        <div className="card-body d-flex flex-column justify-content-between">
                            <div className="mb-3">
                                <div className="p-3 mb-3 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.05)' }}>
                                    <div className="d-flex align-items-center mb-2">
                                        <i className="fab fa-google text-danger fa-lg mr-2"></i>
                                        <strong className="text-white">GCP 인프라 월간 보고서</strong>
                                    </div>
                                    <p className="text-muted small mb-2">GCP VM, 디스크, DB 및 네트워크 점검 결과를 PPTX로 자동 생성합니다.</p>
                                    <Link to="/gcp-report" className="btn btn-blue btn-sm btn-block">보고서 생성</Link>
                                </div>

                                <div className="p-3 rounded" style={{ background: '#1d1e2c', border: '1px solid rgba(255,255,255,0.05)' }}>
                                    <div className="d-flex align-items-center mb-2">
                                        <i className="fab fa-microsoft text-info fa-lg mr-2"></i>
                                        <strong className="text-white">Azure 온보딩 점검표</strong>
                                    </div>
                                    <p className="text-muted small mb-2">Azure 구독 내 VM 인스턴스, 보안 및 자원 점검표를 생성합니다.</p>
                                    <Link to="/azure-checklist" className="btn btn-blue btn-sm btn-block">점검표 생성</Link>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                {/* Right Card: Upcoming Expirations Table */}
                <div className="col-lg-4 mb-4">
                    <div className="card h-100">
                        <div className="card-header d-flex justify-content-between align-items-center">
                            <div>
                                <h5 className="card-category">Notifications</h5>
                                <h4 className="card-title"><i className="fas fa-clock text-warning mr-2"></i>만료 예정 예약</h4>
                            </div>
                            <Link to="/reservations" className="btn btn-sm btn-outline-secondary text-light">전체보기</Link>
                        </div>
                        <div className="card-body p-0">
                            {loading ? (
                                <div className="text-center py-4 text-muted">
                                    <i className="fas fa-spinner fa-spin mr-2"></i>조회 중...
                                </div>
                            ) : upcomingReservations.length === 0 ? (
                                <div className="text-center py-4 text-muted small">
                                    <i className="fas fa-check-circle text-success mr-1"></i>만료 예정인 예약이 없습니다.
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
                                            {upcomingReservations.slice(0, 5).map((res, i) => {
                                                const dday = getDDay(res.expiryDate);
                                                return (
                                                    <tr key={i}>
                                                        <td><strong className="text-white">{res.customerName}</strong></td>
                                                        <td><strong className="text-white">{res.reservationName}</strong></td>
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

            {/* 4. GCP Vertex AI & GenAI Operations Center Panel */}
            <VertexAiOperationsPanel />
        </div>
    );
};

export default DashboardPage;
