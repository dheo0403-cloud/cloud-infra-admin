import React, { useEffect, useState } from 'react';
import { getCustomers, deleteCustomer, InfraCustomer, getJiraIssues, JiraIssueItem } from '../services/api';
import { Link, useNavigate } from 'react-router-dom';

const IntegratedManagementListPage: React.FC = () => {
    const [customers, setCustomers] = useState<InfraCustomer[]>([]);
    const [loading, setLoading] = useState(false);
    const [searchTerm, setSearchTerm] = useState('');
    const [currentPage, setCurrentPage] = useState(1);
    const pageSize = 10;
    const navigate = useNavigate();

    // Jira Modal State
    const [selectedJira, setSelectedJira] = useState<{ customerName: string; projectKey: string; providerType: string } | null>(null);
    const [jiraDays, setJiraDays] = useState<number>(7);
    const [jiraIssues, setJiraIssues] = useState<JiraIssueItem[]>([]);
    const [jiraLoading, setJiraLoading] = useState(false);

    useEffect(() => {
        loadData();
    }, []);

    const loadData = async () => {
        setLoading(true);
        try {
            const res = await getCustomers();
            setCustomers(res.data);
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    const handleDelete = async (id: string) => {
        if (!window.confirm('고객사를 삭제하면 연결된 모든 클라우드 정보가 삭제됩니다. 정말 삭제하시겠습니까?')) return;
        try {
            await deleteCustomer(id);
            loadData();
        } catch (e) {
            alert('삭제 실패');
        }
    };

    const openJiraModal = async (customerName: string, projectKey: string, providerType: string, days = 7) => {
        setSelectedJira({ customerName, projectKey, providerType });
        setJiraDays(days);
        setJiraLoading(true);
        try {
            const res = await getJiraIssues(projectKey, days);
            setJiraIssues(res.data?.issues || []);
        } catch (error) {
            console.error('Failed to load Jira issues', error);
            alert('Jira 이슈를 불러오는 중 오류가 발생했습니다.');
        } finally {
            setJiraLoading(false);
        }
    };

    const handleDaysChange = (days: number) => {
        if (!selectedJira) return;
        openJiraModal(selectedJira.customerName, selectedJira.projectKey, selectedJira.providerType, days);
    };

    // Filter customers by name
    const filteredCustomers = customers.filter(c =>
        c.name.toLowerCase().includes(searchTerm.toLowerCase())
    );

    // Pagination calculations
    const totalPages = Math.ceil(filteredCustomers.length / pageSize);
    const paginatedCustomers = filteredCustomers.slice(
        (currentPage - 1) * pageSize,
        currentPage * pageSize
    );

    return (
        <div className="container-fluid">
            <div className="mb-4 d-flex justify-content-between align-items-center">
                <div>
                    <h5 className="card-category">CUSTOMERS MANAGEMENT</h5>
                    <h2 className="text-white font-weight-bold m-0">
                        <i className="fas fa-users text-info mr-2"></i>고객사 통합 관리 목록
                    </h2>
                    <p className="text-muted small m-0 mt-1">고객사 정보와 연결된 멀티 클라우드(GCP / Azure) 환경을 통합 등록 및 관리합니다.</p>
                </div>
                <Link to="/management/register" className="btn btn-pink btn-lg shadow">
                    <i className="fas fa-plus-circle mr-2"></i>신규 고객 및 환경 등록
                </Link>
            </div>

            <div className="row">
                <div className="col-12">
                    <div className="card shadow">
                        <div className="card-header d-flex justify-content-between align-items-center">
                            <div>
                                <h5 className="card-category">REGISTERED COMPANIES</h5>
                                <h3 className="card-title">등록 고객사 리스트 ({filteredCustomers.length}건)</h3>
                            </div>
                            <div className="card-tools">
                                <div className="input-group input-group-sm" style={{ width: '280px' }}>
                                    <input
                                        type="text"
                                        className="form-control"
                                        placeholder="고객사명 검색..."
                                        value={searchTerm}
                                        onChange={(e) => {
                                            setSearchTerm(e.target.value);
                                            setCurrentPage(1);
                                        }}
                                    />
                                    <div className="input-group-append">
                                        <span className="input-group-text">
                                            <i className="fas fa-search"></i>
                                        </span>
                                    </div>
                                </div>
                            </div>
                        </div>
                        <div className="card-body table-responsive p-0">
                            <table className="table table-hover table-dark mb-0">
                                <thead>
                                    <tr>
                                        <th style={{ width: '65px' }}>순번</th>
                                        <th>고객사명</th>
                                        <th>담당자 / 이메일</th>
                                        <th>보고서 발행 주기</th>
                                        <th>연결된 클라우드 환경</th>
                                        <th>등록일</th>
                                        <th className="text-center">관리</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {loading ? (
                                        <tr><td colSpan={7} className="text-center py-5 text-white"><i className="fas fa-spinner fa-spin fa-2x mr-2"></i>데이터 불러오는 중...</td></tr>
                                    ) : paginatedCustomers.map((c, idx) => {
                                        // Sort environments: AZURE first, GCP second
                                        const sortedEnvs = c.environments ? [...c.environments].sort((a, b) => {
                                            if (a.providerType === 'AZURE' && b.providerType !== 'AZURE') return -1;
                                            if (a.providerType !== 'AZURE' && b.providerType === 'AZURE') return 1;
                                            return a.environmentName.localeCompare(b.environmentName);
                                        }) : [];

                                        return (
                                            <tr key={c.id} data-id={c.id}>
                                                <td className="text-white font-weight-bold">{(currentPage - 1) * pageSize + idx + 1}</td>
                                                <td className="font-weight-bold text-white" style={{ cursor: 'pointer' }} onClick={() => navigate(`/management/edit/${c.id}`)}>
                                                    <span className="text-warning font-weight-bold">{c.name}</span>
                                                    <i className="fas fa-external-link-alt ml-2 small text-info"></i>
                                                    <div className="d-flex flex-wrap gap-1 mt-1">
                                                        {c.jiraProjectKeyGcp && (
                                                            <span 
                                                                className="badge badge-primary font-weight-normal mr-1 shadow-sm" 
                                                                style={{ fontSize: '11px', backgroundColor: '#4285F4', border: '1px solid #70a4f7', cursor: 'pointer' }} 
                                                                title="클릭하여 GCP Jira 이슈 확인"
                                                                onClick={(e) => { e.stopPropagation(); openJiraModal(c.name, c.jiraProjectKeyGcp!, 'GCP', 7); }}>
                                                                <i className="fab fa-google mr-1"></i>{c.jiraProjectKeyGcp} <i className="fas fa-search ml-1" style={{ fontSize: '9px' }}></i>
                                                            </span>
                                                        )}
                                                        {c.jiraProjectKeyAzure && (
                                                            <span 
                                                                className="badge badge-info font-weight-normal shadow-sm" 
                                                                style={{ fontSize: '11px', backgroundColor: '#0078D4', border: '1px solid #50a9ed', cursor: 'pointer' }} 
                                                                title="클릭하여 Azure Jira 이슈 확인"
                                                                onClick={(e) => { e.stopPropagation(); openJiraModal(c.name, c.jiraProjectKeyAzure!, 'AZURE', 7); }}>
                                                                <i className="fab fa-microsoft mr-1"></i>{c.jiraProjectKeyAzure} <i className="fas fa-search ml-1" style={{ fontSize: '9px' }}></i>
                                                            </span>
                                                        )}
                                                    </div>
                                                </td>
                                                <td>
                                                    <div className="text-white font-weight-bold">{c.contactPerson || '-'}</div>
                                                    <div className="text-muted small">{c.contactEmail || '-'}</div>
                                                </td>
                                                <td>
                                                    {c.reportFrequency === 'MONTHLY' ? (
                                                        <span className="badge badge-bd-pink font-weight-bold">월간 (Monthly)</span>
                                                    ) : c.reportFrequency === 'QUARTERLY' ? (
                                                        <span className="badge badge-bd-blue font-weight-bold">분기 (Quarterly)</span>
                                                    ) : (
                                                        <span className="text-muted">-</span>
                                                    )}
                                                </td>
                                                <td>
                                                    {sortedEnvs.length > 0 ? (
                                                        <div className="d-flex flex-wrap gap-2">
                                                             {sortedEnvs.map((env, sIdx) => (
                                                                 <span key={sIdx} className={`badge ${env.providerType === 'GCP' ? 'badge-bd-gcp' : 'badge-bd-azure'} mr-1 p-2 font-weight-bold`}>
                                                                     <i className={`${env.providerType === 'GCP' ? 'fab fa-google' : 'fab fa-microsoft'} mr-1`}></i>
                                                                     {env.providerType} ({env.projects?.length || 0} 프로젝트)
                                                                 </span>
                                                             ))}
                                                        </div>
                                                    ) : (
                                                        <span className="text-muted small">연결된 환경 없음</span>
                                                    )}
                                                </td>
                                                <td className="text-white small font-weight-bold">{c.createdAt?.split('T')[0]}</td>
                                                <td className="text-center">
                                                    <button className="btn btn-outline-info btn-sm mr-2 shadow-sm" onClick={() => navigate(`/management/edit/${c.id}`)} title="수정">
                                                        <i className="fas fa-edit mr-1"></i>수정
                                                    </button>
                                                    <button className="btn btn-outline-danger btn-sm shadow-sm" onClick={() => handleDelete(c.id!)} title="삭제">
                                                        <i className="fas fa-trash mr-1"></i>삭제
                                                    </button>
                                                </td>
                                            </tr>
                                        );
                                    })}
                                    {filteredCustomers.length === 0 && !loading && (
                                        <tr><td colSpan={7} className="text-center py-5 text-muted font-weight-bold">등록된 고객사 또는 환경 정보가 없습니다.</td></tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                        <div className="card-footer d-flex justify-content-between align-items-center">
                            <div className="text-white small font-weight-bold">
                                {filteredCustomers.length > 0 ? (
                                    <>총 {filteredCustomers.length}개 중 {(currentPage - 1) * pageSize + 1}-{Math.min(currentPage * pageSize, filteredCustomers.length)} 표시</>
                                ) : (
                                    <>표시할 항목 없음</>
                                )}
                            </div>
                            {totalPages > 1 && (
                                <ul className="pagination pagination-sm m-0">
                                    <li className={`page-item ${currentPage === 1 ? 'disabled' : ''}`}>
                                        <button className="page-link" onClick={() => setCurrentPage(1)}>&laquo;</button>
                                    </li>
                                    <li className={`page-item ${currentPage === 1 ? 'disabled' : ''}`}>
                                        <button className="page-link" onClick={() => setCurrentPage(currentPage - 1)}>&lsaquo;</button>
                                    </li>
                                    {Array.from({ length: totalPages }, (_, i) => i + 1).map(pageNum => (
                                        <li key={pageNum} className={`page-item ${currentPage === pageNum ? 'active' : ''}`}>
                                            <button className="page-link" onClick={() => setCurrentPage(pageNum)}>{pageNum}</button>
                                        </li>
                                    ))}
                                    <li className={`page-item ${currentPage === totalPages ? 'disabled' : ''}`}>
                                        <button className="page-link" onClick={() => setCurrentPage(currentPage + 1)}>&rsaquo;</button>
                                    </li>
                                    <li className={`page-item ${currentPage === totalPages ? 'disabled' : ''}`}>
                                        <button className="page-link" onClick={() => setCurrentPage(totalPages)}>&raquo;</button>
                                    </li>
                                </ul>
                            )}
                        </div>
                    </div>
                </div>
            </div>

            {/* Jira Issues Modal */}
            {selectedJira && (
                <div className="modal show d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.7)', zIndex: 1050 }}>
                    <div className="modal-dialog modal-xl modal-dialog-centered modal-dialog-scrollable" style={{ maxWidth: '90%' }}>
                        <div className="modal-content bg-dark text-light border border-secondary shadow-lg">
                            <div className="modal-header border-secondary d-flex justify-content-between align-items-center py-3">
                                <h5 className="modal-title font-weight-bold d-flex align-items-center m-0">
                                    <i className={`${selectedJira.providerType === 'GCP' ? 'fab fa-google text-warning' : 'fab fa-microsoft text-info'} mr-2 fa-lg`}></i>
                                    <span>{selectedJira.customerName} - Jira 이슈 확인</span>
                                    <span className="badge badge-secondary ml-3 font-weight-normal" style={{ fontSize: '13px', backgroundColor: '#343a40', border: '1px solid #495057' }}>
                                        프로젝트 키: <strong className="text-warning">{selectedJira.projectKey}</strong> ({selectedJira.providerType})
                                    </span>
                                </h5>
                                <button type="button" className="close text-light" onClick={() => setSelectedJira(null)} style={{ outline: 'none' }}>
                                    <span aria-hidden="true">&times;</span>
                                </button>
                            </div>

                            <div className="modal-body p-3">
                                {/* Period Selector & Summary */}
                                <div className="d-flex justify-content-between align-items-center mb-3 flex-wrap gap-2 pb-2 border-bottom border-secondary">
                                    <div className="d-flex align-items-center">
                                        <span className="text-muted small mr-2 font-weight-bold">조회 기간:</span>
                                        <div className="btn-group btn-group-sm" role="group">
                                            <button 
                                                type="button" 
                                                className={`btn ${jiraDays === 7 ? 'btn-primary font-weight-bold' : 'btn-outline-secondary text-light'}`}
                                                onClick={() => handleDaysChange(7)}>
                                                최근 7일 (1주일)
                                            </button>
                                            <button 
                                                type="button" 
                                                className={`btn ${jiraDays === 14 ? 'btn-primary font-weight-bold' : 'btn-outline-secondary text-light'}`}
                                                onClick={() => handleDaysChange(14)}>
                                                최근 14일 (2주)
                                            </button>
                                            <button 
                                                type="button" 
                                                className={`btn ${jiraDays === 30 ? 'btn-primary font-weight-bold' : 'btn-outline-secondary text-light'}`}
                                                onClick={() => handleDaysChange(30)}>
                                                최근 30일 (1개월)
                                            </button>
                                            <button 
                                                type="button" 
                                                className={`btn ${jiraDays === 0 ? 'btn-primary font-weight-bold' : 'btn-outline-secondary text-light'}`}
                                                onClick={() => handleDaysChange(0)}>
                                                전체 기간
                                            </button>
                                        </div>
                                    </div>
                                    <div className="text-muted small">
                                        조회된 이슈: <strong className="text-warning font-weight-bold" style={{ fontSize: '15px' }}>{jiraIssues.length}</strong>건 (생성일시 최신순 정렬)
                                    </div>
                                </div>

                                {jiraLoading ? (
                                    <div className="text-center py-5">
                                        <div className="spinner-border text-primary" role="status" style={{ width: '3rem', height: '3rem' }}>
                                            <span className="sr-only">로딩 중...</span>
                                        </div>
                                        <div className="text-muted mt-3 font-weight-bold">Jira Cloud API 실시간 이슈 조회 중...</div>
                                    </div>
                                ) : jiraIssues.length === 0 ? (
                                    <div className="text-center py-5 text-muted border border-secondary rounded" style={{ backgroundColor: '#1e2227' }}>
                                        <i className="fas fa-inbox fa-3x mb-3 text-secondary"></i>
                                        <div className="font-weight-bold">선택한 기간 동안 조회된 Jira 이슈가 없습니다.</div>
                                    </div>
                                ) : (
                                    <div className="table-responsive" style={{ maxHeight: '550px' }}>
                                        <table className="table table-dark table-hover table-sm border-secondary m-0">
                                            <thead className="thead-dark text-muted small" style={{ position: 'sticky', top: 0, zIndex: 1, backgroundColor: '#212529' }}>
                                                <tr>
                                                    <th style={{ width: '45px' }} className="text-center">No</th>
                                                    <th style={{ width: '130px' }}>이슈 키</th>
                                                    <th>제목 (Summary)</th>
                                                    <th style={{ width: '90px' }} className="text-center">유형</th>
                                                    <th style={{ width: '90px' }} className="text-center">상태</th>
                                                    <th style={{ width: '110px' }}>담당자</th>
                                                    <th style={{ width: '140px' }} className="text-center">생성일시</th>
                                                    <th style={{ width: '140px' }} className="text-center">수정일시</th>
                                                </tr>
                                            </thead>
                                            <tbody className="small">
                                                {jiraIssues.map((issue, idx) => (
                                                    <tr key={issue.issue_id || idx}>
                                                        <td className="text-center text-muted font-weight-bold align-middle">{idx + 1}</td>
                                                        <td className="align-middle">
                                                            <a 
                                                                href={issue.browse_url} 
                                                                target="_blank" 
                                                                rel="noopener noreferrer" 
                                                                className="text-info font-weight-bold text-decoration-none">
                                                                {issue.issue_key} <i className="fas fa-external-link-alt ml-1" style={{ fontSize: '10px' }}></i>
                                                            </a>
                                                        </td>
                                                        <td className="text-light font-weight-bold align-middle" title={issue.summary}>
                                                            {issue.summary}
                                                        </td>
                                                        <td className="text-center align-middle">
                                                            <span className="badge badge-secondary">{issue.issue_type}</span>
                                                        </td>
                                                        <td className="text-center align-middle">
                                                            <span className={`badge ${
                                                                issue.status_name.includes('완료') || issue.status_name.includes('해결') || issue.status_name.toLowerCase().includes('done') 
                                                                    ? 'badge-success' 
                                                                    : issue.status_name.includes('진행') || issue.status_name.toLowerCase().includes('progress')
                                                                    ? 'badge-primary'
                                                                    : 'badge-warning text-dark'
                                                            }`}>
                                                                {issue.status_name}
                                                            </span>
                                                        </td>
                                                        <td className="text-muted align-middle">{issue.assignee_name || '미지정'}</td>
                                                        <td className="text-center text-muted align-middle">
                                                            {issue.created_at ? issue.created_at.replace('T', ' ').substring(0, 16) : '-'}
                                                        </td>
                                                        <td className="text-center text-muted align-middle">
                                                            {issue.updated_at ? issue.updated_at.replace('T', ' ').substring(0, 16) : '-'}
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                )}
                            </div>

                            <div className="modal-footer border-secondary d-flex justify-content-between py-2">
                                <div className="text-muted small">
                                    <i className="fas fa-info-circle mr-1 text-info"></i>이슈 키를 클릭하면 Atlassian Jira 해당 이슈로 새 탭에서 즉시 이동합니다.
                                </div>
                                <button type="button" className="btn btn-secondary btn-sm" onClick={() => setSelectedJira(null)}>
                                    닫기
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default IntegratedManagementListPage;
