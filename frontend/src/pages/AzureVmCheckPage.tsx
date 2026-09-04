import React, { useEffect, useState } from 'react';
import { getCustomers, getEnvironmentsByCustomer, runAudit, getAuditStatus, downloadAuditReport, InfraCustomer, InfraEnvironment } from '../services/api';

const AzureVmCheckPage: React.FC = () => {
    const [customers, setCustomers] = useState<InfraCustomer[]>([]);
    const [environments, setEnvironments] = useState<InfraEnvironment[]>([]);
    
    const [selectedCustomerId, setSelectedCustomerId] = useState<string>('');
    const [selectedEnvId, setSelectedEnvId] = useState<string>('');
    const [auditing, setAuditing] = useState(false);
    const [auditStatusText, setAuditStatusText] = useState('Azure 인프라 점검표 생성 (Excel)');

    useEffect(() => {
        loadCustomers();
    }, []);

    const loadCustomers = async () => {
        try {
            const res = await getCustomers();
            const azureCustomers = res.data.filter(c => 
                c.environments && c.environments.some(env => env.providerType === 'AZURE')
            );
            setCustomers(azureCustomers);
        } catch (e) {
            console.error(e);
        }
    };

    const handleCustomerChange = async (e: React.ChangeEvent<HTMLSelectElement>) => {
        const cid = e.target.value;
        setSelectedCustomerId(cid);
        setSelectedEnvId('');
        if (cid) {
            const res = await getEnvironmentsByCustomer(cid);
            setEnvironments(res.data.filter(env => env.providerType === 'AZURE'));
        } else {
            setEnvironments([]);
        }
    };

    const handleEnvChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        setSelectedEnvId(e.target.value);
    };

    const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

    const handleGenerateAudit = async () => {
        if (!selectedEnvId) return;
        setAuditing(true);
        setAuditStatusText('Azure 점검표 작성 중... (최대 수 분이 소요될 수 있습니다)');
        
        try {
            const auditRes = await runAudit(selectedEnvId);
            const reportId = auditRes.data.id as string;
            
            if (!reportId) {
                throw new Error('감사 보고서 ID를 생성하지 못했습니다.');
            }
            
            while (true) {
                await sleep(3000);
                const statusRes = await getAuditStatus(reportId);
                const status = statusRes.data;
                if (status === 'COMPLETED') {
                    break;
                } else if (status === 'FAILED') {
                    throw new Error('서버에서 감사 보고서 생성에 실패했습니다.');
                }
            }
            
            setAuditStatusText('파일 다운로드 중...');
            const downloadRes = await downloadAuditReport(reportId);
            
            const disposition = downloadRes.headers['content-disposition'];
            let filename = `Azure_Audit_Report_${new Date().toISOString().split('T')[0]}.xlsm`;
            if (disposition && disposition.indexOf('filename=') !== -1) {
                const filenameRegex = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/;
                const matches = filenameRegex.exec(disposition);
                if (matches != null && matches[1]) {
                    filename = decodeURIComponent(matches[1].replace(/['"]/g, ''));
                }
            }

            const blobType = filename.endsWith('.xlsm') 
                ? 'application/vnd.ms-excel.sheet.macroEnabled.12' 
                : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

            const url = window.URL.createObjectURL(new Blob([downloadRes.data], { type: blobType }));
            const link = document.createElement('a');
            link.href = url;
            link.setAttribute('download', filename);
            document.body.appendChild(link);
            link.click();
            link.remove();
        } catch (e) {
            console.error(e);
            alert('Azure 인프라 감사 보고서 생성 실패');
        } finally {
            setAuditing(false);
            setAuditStatusText('Azure 인프라 점검표 생성 (Excel)');
        }
    };

    return (
        <div className="container-fluid">
            <div className="content-header mb-3">
                <h1 className="m-0 text-light font-weight-bold">
                    <i className="fab fa-microsoft text-info mr-2"></i>Azure 온보딩 점검표
                </h1>
                <p className="text-muted">Azure 구독 환경의 자원, 보안, 네트워크 구성 요소를 점검하고 Excel 보고서로 다운로드합니다.</p>
            </div>

            <div className="card bg-dark border-secondary shadow-sm mb-4">
                <div className="card-header border-secondary">
                    <h5 className="card-title text-light mb-0">대상 선택 및 점검표 생성</h5>
                </div>
                <div className="card-body">
                    <div className="row align-items-end">
                        <div className="col-md-4 mb-3 mb-md-0">
                            <label className="text-muted">고객사 선택</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedCustomerId} onChange={handleCustomerChange}>
                                <option value="">고객사를 선택하세요</option>
                                {customers.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                            </select>
                        </div>
                        <div className="col-md-4 mb-3 mb-md-0">
                            <label className="text-muted">Azure 환경 선택</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedEnvId} onChange={handleEnvChange} disabled={!selectedCustomerId}>
                                <option value="">환경을 선택하세요</option>
                                {environments.map(e => (
                                    <option key={e.id} value={e.id}>
                                        {e.environmentName} {e.azureTenantId ? `(Tenant: ${e.azureTenantId})` : ''}
                                    </option>
                                ))}
                            </select>
                        </div>
                        <div className="col-md-4">
                            <button className="btn btn-info btn-block shadow-sm" onClick={handleGenerateAudit} disabled={!selectedEnvId || auditing}>
                                <i className={`fas ${auditing ? 'fa-spinner fa-spin' : 'fa-file-excel'} mr-2`}></i>
                                {auditStatusText}
                            </button>
                        </div>
                    </div>
                </div>
            </div>

            {/* Features Description Card */}
            <div className="card bg-dark border-secondary shadow-sm">
                <div className="card-header border-secondary">
                    <h6 className="card-title text-light mb-0"><i className="fas fa-info-circle text-info mr-2"></i>점검 항목 안내</h6>
                </div>
                <div className="card-body">
                    <div className="row text-muted small">
                        <div className="col-md-4">
                            <ul className="pl-3">
                                <li>Azure Virtual Machines 및 Disk 자원 분석</li>
                                <li>Virtual Networks 및 Network Security Groups (NSG)</li>
                            </ul>
                        </div>
                        <div className="col-md-4">
                            <ul className="pl-3">
                                <li>Azure Active Directory (Entra ID) 역할 / IAM</li>
                                <li>Load Balancers 및 Public IP 자원 현황</li>
                            </ul>
                        </div>
                        <div className="col-md-4">
                            <ul className="pl-3">
                                <li>보안 정책 및 미사용 디스크 검출</li>
                                <li>Reserved Instances (RI) 계약 만료 현황</li>
                            </ul>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default AzureVmCheckPage;
