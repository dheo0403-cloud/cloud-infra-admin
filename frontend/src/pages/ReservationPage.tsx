import React, { useEffect, useState } from 'react';
import { getCustomers, getEnvironmentsByCustomer, getReservations, refreshReservations, getUpcomingExpiryReservations, getAzureAppCredentials, InfraCustomer, InfraEnvironment, ReservationDto } from '../services/api';

const ReservationPage: React.FC = () => {
    const [customers, setCustomers] = useState<InfraCustomer[]>([]);
    const [environments, setEnvironments] = useState<InfraEnvironment[]>([]);
    const [reservations, setReservations] = useState<ReservationDto[]>([]);
    const [upcomingReservations, setUpcomingReservations] = useState<ReservationDto[]>([]);

    const [selectedCsp, setSelectedCsp] = useState<string>('');
    const [upcomingLoading, setUpcomingLoading] = useState<boolean>(false);
    const [selectedCustomerId, setSelectedCustomerId] = useState<string>('');
    const [selectedEnvId, setSelectedEnvId] = useState<string>('');
    const [selectedProjectId, setSelectedProjectId] = useState<string>(''); // 선택된 개별 프로젝트/구독 ID
    const [selectedProvider, setSelectedProvider] = useState<string>('');
    const [activeTab, setActiveTab] = useState<string>('AZURE');
    const [loading, setLoading] = useState(false);
    const [refreshing, setRefreshing] = useState(false);
    // 처음 화면 탭: 만료 임박 예약(RI/CUD) / Azure 앱 자격 증명 만료
    const [overviewTab, setOverviewTab] = useState<'RESERVATION' | 'APP_CREDENTIAL'>('RESERVATION');
    const [appCredentials, setAppCredentials] = useState<ReservationDto[]>([]);
    const [appCredLoading, setAppCredLoading] = useState<boolean>(false);

    const handleCspChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        const csp = e.target.value;
        setSelectedCsp(csp);
        setSelectedCustomerId('');
        setSelectedEnvId('');
        setSelectedProjectId('');
        setSelectedProvider('');
        setEnvironments([]);
        setReservations([]);
    };

    useEffect(() => {
        loadCustomers();
        loadUpcomingReservations();
        loadAppCredentials();
    }, []);

    const loadAppCredentials = async () => {
        setAppCredLoading(true);
        try {
            const res = await getAzureAppCredentials();
            setAppCredentials(res.data || []);
        } catch (e) {
            console.error(e);
        } finally {
            setAppCredLoading(false);
        }
    };

    const loadUpcomingReservations = async () => {
        setUpcomingLoading(true);
        try {
            const res = await getUpcomingExpiryReservations();
            const filtered = (res.data || []).filter(r => r.provider !== 'AZURE_APP' && r.type !== 'CLIENT_SECRET' && r.type !== 'CERTIFICATE');
            setUpcomingReservations(filtered);
        } catch (e) {
            console.error(e);
        } finally {
            setUpcomingLoading(false);
        }
    };

    const loadCustomers = async () => {
        try {
            const res = await getCustomers();
            setCustomers(res.data);
        } catch (e) {
            console.error(e);
        }
    };

    const handleCustomerChange = async (e: React.ChangeEvent<HTMLSelectElement>) => {
        const cid = e.target.value;
        setSelectedCustomerId(cid);
        setSelectedEnvId('');
        setSelectedProjectId('');
        setReservations([]);
        if (cid) {
            const res = await getEnvironmentsByCustomer(cid);
            setEnvironments(res.data);
        } else {
            setEnvironments([]);
        }
    };

    const handleEnvChange = async (e: React.ChangeEvent<HTMLSelectElement>) => {
        const envId = e.target.value;
        setSelectedEnvId(envId);
        if (envId) {
            const env = environments.find(en => en.id === envId);
            if (env) {
                const provider = env.providerType || '';
                setSelectedProvider(provider);
                setActiveTab(provider === 'GCP' ? 'GCP' : 'AZURE');
                if (env.projects && env.projects.length > 0) {
                    setSelectedProjectId(env.projects[0].projectId);
                } else {
                    setSelectedProjectId('');
                }
            }
            await loadReservations(envId);
        } else {
            setSelectedProjectId('');
            setReservations([]);
        }
    };

    const loadReservations = async (envId: string) => {
        setLoading(true);
        try {
            const res = await getReservations(envId);
            setReservations(res.data);
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    const handleRefresh = async () => {
        if (!selectedEnvId) return;
        setRefreshing(true);
        try {
            const res = await refreshReservations(selectedEnvId);
            setReservations(res.data);
        } catch (e) {
            console.error(e);
            alert('데이터 새로고침에 실패했습니다.');
        } finally {
            setRefreshing(false);
        }
    };

    const getDday = (expiryDate: string): number => {
        if (!expiryDate) return 0;
        const today = new Date();
        const expiry = new Date(expiryDate);
        const diff = Math.ceil((expiry.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
        return diff;
    };

    const getStatusBadge = (status: string, expiryDate: string) => {
        if (status === 'CANCELLED') {
            return <span className="badge badge-secondary">취소됨</span>;
        }
        const dday = getDday(expiryDate);
        if (status === 'EXPIRED' || dday < 0) {
            return <span className="badge badge-dark text-muted">만료됨</span>;
        }
        if (dday <= 30) {
            return <span className="badge badge-danger font-weight-bold px-2 py-1" style={{ backgroundColor: '#ff3860', color: '#ffffff', boxShadow: '0 2px 8px rgba(255, 56, 96, 0.4)' }}>만료 임박</span>;
        }
        return <span className="badge badge-bd-teal">활성</span>;
    };

    const getDdayText = (status: string, expiryDate: string) => {
        if (status === 'CANCELLED') return <span className="text-muted">-</span>;
        const dday = getDday(expiryDate);
        if (dday < 0) return <span className="text-danger font-weight-bold">D+{Math.abs(dday)}</span>;
        if (dday === 0) return <span className="text-danger font-weight-bold" style={{ color: '#ff3860' }}>D-Day</span>;
        if (dday <= 30) return <span className="text-danger font-weight-bold" style={{ color: '#ff3860', fontSize: '0.92rem' }}>D-{dday}</span>;
        return <span className="text-success">D-{dday}</span>;
    };

    const getRowStyle = (r: ReservationDto): React.CSSProperties => {
        if (r.status === 'CANCELLED') return { opacity: 0.4 };
        return {};
    };

    const getRowClass = (_r: ReservationDto) => {
        return '';
    };

    // 현재 선택된 환경 객체 찾기
    const currentEnv = environments.find(e => e.id === selectedEnvId);

    const filteredCustomers = customers.filter(c => {
        if (!selectedCsp) return true;
        return c.environments?.some(env => env.providerType === selectedCsp);
    });

    const filteredEnvironments = environments.filter(env => {
        if (!selectedCsp) return true;
        return env.providerType === selectedCsp;
    });

    // 필터링 및 정렬: provider 탭 + 선택된 개별 프로젝트/구독
    const filteredReservations = reservations
        .filter(r => r.provider === activeTab)
        .filter(r => r.projectId === selectedProjectId)
        .filter(r => r.provider !== 'AZURE_APP' && r.type !== 'CLIENT_SECRET' && r.type !== 'CERTIFICATE')
        .sort((a, b) => {
            const getSortWeight = (r: ReservationDto): number => {
                if (r.status === 'CANCELLED') return 2;
                const dday = getDday(r.expiryDate);
                if (r.status === 'EXPIRED' || dday < 0) return 3;
                return 1; // 활성
            };

            const wA = getSortWeight(a);
            const wB = getSortWeight(b);
            if (wA !== wB) return wA - wB;

            // 같은 상태 그룹 내에서는 D-Day 오름차순 정렬
            const ddayA = getDday(a.expiryDate);
            const ddayB = getDday(b.expiryDate);
            return ddayA - ddayB;
        });

    // 앱 자격 증명: 남은 키를 D-Day 오름차순, 이미 만료된 키는 아래로
    const sortedAppCredentials = [...appCredentials].sort((a, b) => {
        const expiredA = getDday(a.expiryDate) < 0 ? 1 : 0;
        const expiredB = getDday(b.expiryDate) < 0 ? 1 : 0;
        if (expiredA !== expiredB) return expiredA - expiredB;
        return getDday(a.expiryDate) - getDday(b.expiryDate);
    });
    const appCredWithin30 = appCredentials.filter(r => { const d = getDday(r.expiryDate); return d >= 0 && d <= 30; }).length;
    const appCredExpired = appCredentials.filter(r => getDday(r.expiryDate) < 0).length;
    const credentialTypeLabel = (type: string) => type === 'CLIENT_SECRET' ? '클라이언트 비밀값' : type === 'CERTIFICATE' ? '인증서' : type;

    const overviewTabStyle = (active: boolean): React.CSSProperties => ({
        backgroundColor: active ? '#17a2b8' : 'transparent',
        color: active ? '#fff' : '#6c757d',
        border: 'none',
        borderRadius: '4px 4px 0 0',
        fontWeight: active ? 'bold' : 'normal',
        padding: '10px 20px',
    });

    return (
        <div className="container-fluid">
            <div className="content-header">
                <h1 className="m-0 text-light">
                    <i className="fas fa-calendar-check mr-2"></i>예약 관리
                </h1>
                <p className="text-muted">Azure RI 및 GCP CUD 만료일 현황을 확인합니다.</p>
            </div>

            {/* Selection Header */}
            <div className="card bg-dark border-0 shadow-sm mb-4">
                <div className="card-body">
                    <div className="row align-items-end">
                        <div className="col-md-2">
                            <label>CSP</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedCsp} onChange={handleCspChange}>
                                <option value="">전체 CSP</option>
                                <option value="AZURE">Azure</option>
                                <option value="GCP">GCP</option>
                            </select>
                        </div>
                        <div className="col-md-3">
                            <label>고객사</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedCustomerId} onChange={handleCustomerChange}>
                                <option value="">고객사 선택</option>
                                {filteredCustomers.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                            </select>
                        </div>
                        <div className="col-md-3">
                            <label>환경</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedEnvId} onChange={handleEnvChange}>
                                <option value="">환경 선택</option>
                                {filteredEnvironments.map(e => (
                                    <option key={e.id} value={e.id}>
                                        [{e.providerType}] {e.environmentName}
                                    </option>
                                ))}
                            </select>
                        </div>
                        {/* 프로젝트 / 구독 개별 선택 드롭다운 */}
                        <div className="col-md-2">
                            <label>{selectedProvider === 'GCP' ? '프로젝트' : '구독'}</label>
                            <select 
                                className="form-control bg-secondary text-light border-0" 
                                value={selectedProjectId} 
                                onChange={e => setSelectedProjectId(e.target.value)}
                                disabled={!selectedEnvId || !currentEnv?.projects?.length}
                            >
                                <option value="">{selectedProvider === 'GCP' ? '프로젝트 선택' : selectedProvider === 'AZURE' ? '구독 선택' : '선택'}</option>
                                {currentEnv?.projects?.map(p => (
                                    <option key={p.projectId} value={p.projectId}>
                                        {p.projectId}
                                    </option>
                                ))}
                            </select>
                        </div>
                        <div className="col-md-2">
                            <button className="btn btn-outline-info btn-block" onClick={handleRefresh} disabled={!selectedEnvId || refreshing}>
                                <i className={`fas ${refreshing ? 'fa-spinner fa-spin' : 'fa-sync-alt'} mr-2`}></i>
                                {refreshing ? '새로고침 중...' : '수동 새로고침'}
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            {/* 처음에 아무것도 선택하지 않았을 때 (selectedEnvId 가 없을 때) */}
            {!selectedEnvId && (
                <div className="card bg-dark border-0 shadow-sm mb-4">
                    <div className="card-header p-0" style={{ borderBottom: '2px solid #495057' }}>
                        <ul className="nav nav-tabs" style={{ borderBottom: 'none' }}>
                            <li className="nav-item">
                                <button className="nav-link" id="tab-overview-reservation" onClick={() => setOverviewTab('RESERVATION')} style={overviewTabStyle(overviewTab === 'RESERVATION')}>
                                    <i className="fas fa-calendar-check mr-1"></i> 만료 임박 예약 (RI/CUD)
                                    <span className="badge ml-2" style={{ backgroundColor: overviewTab === 'RESERVATION' ? 'rgba(255,255,255,0.3)' : '#495057' }}>{upcomingReservations.length}</span>
                                </button>
                            </li>
                            <li className="nav-item">
                                <button className="nav-link" id="tab-overview-app-credential" onClick={() => setOverviewTab('APP_CREDENTIAL')} style={overviewTabStyle(overviewTab === 'APP_CREDENTIAL')}>
                                    <i className="fas fa-key mr-1"></i> 앱 자격 증명 만료 (Azure)
                                    <span className="badge ml-2" style={{ backgroundColor: appCredWithin30 > 0 ? '#ff3860' : (overviewTab === 'APP_CREDENTIAL' ? 'rgba(255,255,255,0.3)' : '#495057'), color: '#fff' }}>{appCredentials.length}</span>
                                </button>
                            </li>
                        </ul>
                    </div>
                    {overviewTab === 'APP_CREDENTIAL' ? (
                        <>
                            <div className="card-header border-0">
                                <h3 className="card-title text-light">
                                    <i className="fas fa-key text-warning mr-2"></i>
                                    Azure 앱 자격 증명 만료 현황
                                </h3>
                                <p className="text-muted small mb-0 mt-1">모든 고객사 테넌트의 접속용 앱(mz-api*) 클라이언트 비밀값·인증서 만료일입니다. 30일 이내는 '만료 임박'으로 표시합니다.</p>
                            </div>
                            <div className="card-body p-0">
                                {appCredLoading ? (
                                    <div className="text-center p-5">
                                        <i className="fas fa-spinner fa-spin fa-2x text-info"></i>
                                        <p className="mt-3 text-muted">데이터를 불러오는 중...</p>
                                    </div>
                                ) : sortedAppCredentials.length === 0 ? (
                                    <div className="text-center p-5">
                                        <i className="fas fa-inbox fa-2x text-muted"></i>
                                        <p className="mt-3 text-muted">수집된 Azure 앱 자격 증명이 없습니다.</p>
                                    </div>
                                ) : (
                                    <div className="table-responsive">
                                        <table className="table table-dark mb-0" id="app-credential-table">
                                            <thead>
                                                <tr>
                                                    <th>고객사</th>
                                                    <th>앱 이름</th>
                                                    <th>종류</th>
                                                    <th>상태</th>
                                                    <th>만료일</th>
                                                    <th>D-Day</th>
                                                    <th>키 ID</th>
                                                    <th>구독 ID</th>
                                                    <th>마지막 수집</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {sortedAppCredentials.map((r, idx) => (
                                                    <tr key={idx} style={getDday(r.expiryDate) < 0 ? { opacity: 0.5 } : {}}>
                                                        <td><strong className="text-white">{r.customerName}</strong></td>
                                                        <td><strong className="text-white">{r.reservationName}</strong></td>
                                                        <td><span style={{ color: '#cbd5e1' }}>{credentialTypeLabel(r.type)}</span></td>
                                                        <td>{getStatusBadge(r.status, r.expiryDate)}</td>
                                                        <td><span style={{ color: '#cbd5e1' }}>{r.expiryDate}</span></td>
                                                        <td>{getDdayText(r.status, r.expiryDate)}</td>
                                                        <td><code style={{ color: '#cbd5e1', backgroundColor: 'rgba(0,0,0,0.25)', padding: '2px 6px', borderRadius: '4px' }}>{r.resourceDetail}</code></td>
                                                        <td><code style={{ color: '#00f2c3', backgroundColor: 'rgba(0,0,0,0.25)', padding: '2px 6px', borderRadius: '4px' }}>{r.projectId}</code></td>
                                                        {/* 3일 넘게 수집되지 않은 고객사는 경고색 (키 만료·권한 오류로 수집 중단 가능) */}
                                                        <td>{getDday(r.snapshotDate.substring(0, 10)) < -3
                                                            ? <span className="text-warning font-weight-bold" title="최근 수집 실패 — 키 만료·권한 확인 필요">{r.snapshotDate.substring(0, 10)} (수집 중단)</span>
                                                            : <span style={{ color: '#cbd5e1' }}>{r.snapshotDate.substring(0, 10)}</span>}</td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                )}
                            </div>
                            {sortedAppCredentials.length > 0 && (
                                <div className="card-footer text-muted small">
                                    <i className="fas fa-info-circle mr-1"></i>
                                    총 {sortedAppCredentials.length}건 | 30일 이내 {appCredWithin30}건 | 만료 {appCredExpired}건 | Slack 알림: 평일 10시, 만료 10일 이내
                                </div>
                            )}
                        </>
                    ) : (
                    <>
                    <div className="card-header border-0">
                        <h3 className="card-title text-light">
                            <i className="fas fa-exclamation-triangle text-warning mr-2"></i>
                            만료 임박 예약 현황 (100일 미만)
                        </h3>
                        <p className="text-muted small mb-0 mt-1">모든 고객사의 Azure RI 및 GCP CUD 중 만료일이 100일 미만으로 남은 예약을 통합 조회합니다.</p>
                    </div>
                    <div className="card-body p-0">
                        {upcomingLoading ? (
                            <div className="text-center p-5">
                                <i className="fas fa-spinner fa-spin fa-2x text-info"></i>
                                <p className="mt-3 text-muted">데이터를 불러오는 중...</p>
                            </div>
                        ) : upcomingReservations.length === 0 ? (
                            <div className="text-center p-5">
                                <i className="fas fa-check-circle fa-2x text-success"></i>
                                <p className="mt-3 text-muted">100일 이내에 만료 예정인 예약 데이터가 없습니다.</p>
                            </div>
                        ) : (
                            <div className="table-responsive">
                                <table className="table table-dark mb-0">
                                    <thead>
                                        <tr>
                                            <th>고객사</th>
                                            <th>CSP</th>
                                            <th>이름</th>
                                            <th>상태</th>
                                            <th>만료일</th>
                                            <th>D-Day</th>
                                            <th>유형/카테고리</th>
                                            <th>플랜</th>
                                            <th>리전</th>
                                            <th>리소스/SKU</th>
                                            <th>프로젝트/구독 ID</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {upcomingReservations.map((r, idx) => (
                                            <tr key={idx} style={getRowStyle(r)}>
                                                <td><strong className="text-white">{r.customerName}</strong></td>
                                                <td>
                                                    {r.provider === 'GCP' ? (
                                                        <span className="badge badge-bd-gcp"><i className="fab fa-google mr-1"></i>GCP</span>
                                                    ) : (
                                                        <span className="badge badge-bd-azure"><i className="fab fa-microsoft mr-1"></i>Azure</span>
                                                    )}
                                                </td>
                                                <td><strong className="text-white">{r.reservationName}</strong></td>
                                                <td>{getStatusBadge(r.status, r.expiryDate)}</td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.expiryDate}</span></td>
                                                <td>{getDdayText(r.status, r.expiryDate)}</td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.type}</span></td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.plan}</span></td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.region}</span></td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.resourceDetail}</span></td>
                                                <td><code style={{ color: '#00f2c3', backgroundColor: 'rgba(0,0,0,0.25)', padding: '2px 6px', borderRadius: '4px' }}>{r.projectId}</code></td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                    {upcomingReservations.length > 0 && (
                        <div className="card-footer text-muted small">
                            <i className="fas fa-info-circle mr-1"></i>
                            총 {upcomingReservations.length}건의 만료 임박(100일 미만) 예약이 존재합니다.
                        </div>
                    )}
                    </>
                    )}
                </div>
            )}

            {/* 환경은 선택되었으나 프로젝트/구독이 선택되지 않았을 때의 안내 문구 */}
            {selectedEnvId && !selectedProjectId && (
                <div className="card bg-dark border-0 shadow-sm p-5 text-center">
                    <i className="fas fa-arrow-up fa-2x text-info mb-3"></i>
                    <h5 className="text-light">
                        {selectedProvider === 'GCP' ? '프로젝트를 선택해 주세요.' : '구독을 선택해 주세요.'}
                    </h5>
                    <p className="text-muted mb-0">상단의 드롭다운에서 조회할 대상 프로젝트/구독을 선택해 주세요.</p>
                </div>
            )}

            {/* Tabs & Table */}
            {selectedEnvId && selectedProjectId && (
                <div className="card bg-dark border-0 shadow-sm">
                    <div className="card-header p-0" style={{ borderBottom: '2px solid #495057' }}>
                        <ul className="nav nav-tabs" style={{ borderBottom: 'none' }}>
                            <li className="nav-item">
                                <button
                                    className="nav-link"
                                    onClick={() => setActiveTab('AZURE')}
                                    style={{
                                        backgroundColor: activeTab === 'AZURE' ? '#17a2b8' : 'transparent',
                                        color: activeTab === 'AZURE' ? '#fff' : '#6c757d',
                                        border: 'none',
                                        borderRadius: '4px 4px 0 0',
                                        fontWeight: activeTab === 'AZURE' ? 'bold' : 'normal',
                                        padding: '10px 20px',
                                    }}
                                >
                                    <i className="fab fa-microsoft mr-1"></i> Azure RI
                                    <span className="badge ml-2" style={{ backgroundColor: activeTab === 'AZURE' ? 'rgba(255,255,255,0.3)' : '#495057' }}>
                                        {reservations.filter(r => r.provider === 'AZURE' && r.projectId === selectedProjectId).length}
                                    </span>
                                </button>
                            </li>
                            <li className="nav-item">
                                <button
                                    className="nav-link"
                                    onClick={() => setActiveTab('GCP')}
                                    style={{
                                        backgroundColor: activeTab === 'GCP' ? '#17a2b8' : 'transparent',
                                        color: activeTab === 'GCP' ? '#fff' : '#6c757d',
                                        border: 'none',
                                        borderRadius: '4px 4px 0 0',
                                        fontWeight: activeTab === 'GCP' ? 'bold' : 'normal',
                                        padding: '10px 20px',
                                    }}
                                >
                                    <i className="fab fa-google mr-1"></i> GCP CUD
                                    <span className="badge ml-2" style={{ backgroundColor: activeTab === 'GCP' ? 'rgba(255,255,255,0.3)' : '#495057' }}>
                                        {reservations.filter(r => r.provider === 'GCP' && r.projectId === selectedProjectId).length}
                                    </span>
                                </button>
                            </li>
                        </ul>
                    </div>
                    <div className="card-body p-0">
                        {loading ? (
                            <div className="text-center p-5">
                                <i className="fas fa-spinner fa-spin fa-2x text-info"></i>
                                <p className="mt-3 text-muted">데이터를 불러오는 중...</p>
                            </div>
                        ) : filteredReservations.length === 0 ? (
                            <div className="text-center p-5">
                                <i className="fas fa-inbox fa-2x text-muted"></i>
                                <p className="mt-3 text-muted">
                                    {activeTab === selectedProvider
                                        ? '예약 데이터가 없습니다. "수동 새로고침" 버튼을 눌러 데이터를 가져와 주세요.'
                                        : `이 환경은 ${selectedProvider} 환경입니다. ${selectedProvider} 탭을 선택해 주세요.`}
                                </p>
                            </div>
                        ) : (
                            <div className="table-responsive">
                                <table className="table table-dark mb-0">
                                    <thead>
                                        <tr>
                                            <th>이름</th>
                                            <th>상태</th>
                                            <th>만료일</th>
                                            <th>D-Day</th>
                                            <th>{activeTab === 'AZURE' ? '유형' : '카테고리'}</th>
                                            <th>플랜</th>
                                            <th>리전</th>
                                            {activeTab === 'AZURE' && <th>범위</th>}
                                            <th>{activeTab === 'AZURE' ? 'SKU' : '리소스'}</th>
                                            <th>{selectedProvider === 'GCP' ? '프로젝트 ID' : '구독 ID'}</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {filteredReservations.map((r, idx) => (
                                            <tr key={idx} style={getRowStyle(r)}>
                                                <td><strong className="text-white">{r.reservationName}</strong></td>
                                                <td>{getStatusBadge(r.status, r.expiryDate)}</td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.expiryDate}</span></td>
                                                <td>{getDdayText(r.status, r.expiryDate)}</td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.type}</span></td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.plan}</span></td>
                                                <td><span style={{ color: '#cbd5e1' }}>{r.region}</span></td>
                                                {activeTab === 'AZURE' && <td><span style={{ color: '#cbd5e1' }}>{r.scope}</span></td>}
                                                <td><span style={{ color: '#cbd5e1' }}>{r.resourceDetail}</span></td>
                                                <td><code style={{ color: '#00f2c3', backgroundColor: 'rgba(0,0,0,0.25)', padding: '2px 6px', borderRadius: '4px' }}>{r.projectId}</code></td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                    {filteredReservations.length > 0 && (
                                                        <div className="card-footer text-muted small">
                                                            <i className="fas fa-info-circle mr-1"></i>
                                                            마지막 수집일: {filteredReservations[0]?.snapshotDate || '-'} | 
                                                            총 {filteredReservations.length}건 | 
                                                            활성 {filteredReservations.filter(r => r.status === 'ACTIVE' && getDday(r.expiryDate) >= 0).length}건 | 
                                                            만료 {filteredReservations.filter(r => r.status !== 'CANCELLED' && (r.status === 'EXPIRED' || getDday(r.expiryDate) < 0)).length}건 | 
                                                            취소 {filteredReservations.filter(r => r.status === 'CANCELLED').length}건
                                                        </div>
                                                    )}
                </div>
            )}
        </div>
    );
};

export default ReservationPage;
