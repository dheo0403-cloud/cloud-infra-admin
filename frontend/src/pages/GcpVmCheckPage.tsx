import React, { useEffect, useState } from 'react';
import { getCustomers, getEnvironmentsByCustomer, runAudit, getAuditStatus, downloadAuditReport, InfraCustomer, InfraEnvironment } from '../services/api';

const GcpVmCheckPage: React.FC = () => {
    const [customers, setCustomers] = useState<InfraCustomer[]>([]);
    const [environments, setEnvironments] = useState<InfraEnvironment[]>([]);
    
    const [selectedCustomerId, setSelectedCustomerId] = useState<string>('');
    const [selectedEnvId, setSelectedEnvId] = useState<string>('');
    const [auditing, setAuditing] = useState(false);
    const [auditStatusText, setAuditStatusText] = useState('전체 인프라 점검표 생성 (Excel)');

    useEffect(() => {
        loadCustomers();
    }, []);

    const loadCustomers = async () => {
        try {
            const res = await getCustomers();
            const gcpCustomers = res.data.filter(c => 
                c.environments && c.environments.some(env => env.providerType === 'GCP')
            );
            setCustomers(gcpCustomers);
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
            setEnvironments(res.data.filter(env => env.providerType === 'GCP'));
        }
    };

    const handleEnvChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
        setSelectedEnvId(e.target.value);
    };

    const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

    const handleGenerateAudit = async () => {
        if (!selectedEnvId) return;
        setAuditing(true);
        setAuditStatusText('보고서 작성 중... (최대 수 분이 소요될 수 있습니다)');
        
        try {
            // 1. Run Audit (returns immediately with IN_PROGRESS status)
            const auditRes = await runAudit(selectedEnvId as string);
            const reportId = auditRes.data.id as string;
            
            if (!reportId) {
                throw new Error('감사 보고서 ID를 생성하지 못했습니다.');
            }
            
            // 2. Poll for Status
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
            
            // 3. Download Report
            setAuditStatusText('파일 다운로드 중...');
            const downloadRes = await downloadAuditReport(reportId);
            
            // 4. Extract Filename from Header if possible, or use default
            const disposition = downloadRes.headers['content-disposition'];
            let filename = `GCP_Audit_Report_${new Date().toISOString().split('T')[0]}.xlsm`;
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
            alert('인프라 감사 보고서 생성 실패');
        } finally {
            setAuditing(false);
        }
    };

    return (
        <div className="container-fluid">
            <div className="content-header">
                <h1 className="m-0 text-light">GCP 인프라 점검</h1>
            </div>

            {/* Selection Header */}
            <div className="card bg-dark border-0 shadow-sm mb-4">
                <div className="card-body">
                    <div className="row align-items-end">
                        <div className="col-md-4">
                            <label>고객사</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedCustomerId} onChange={handleCustomerChange}>
                                <option value="">고객사 선택</option>
                                {customers.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                            </select>
                        </div>
                        <div className="col-md-4">
                            <label>GCP 환경</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedEnvId} onChange={handleEnvChange}>
                                <option value="">환경 선택</option>
                                {environments.map(e => (
                                    <option key={e.id} value={e.id}>
                                        {e.environmentName} ({e.projects ? e.projects.map(p => p.projectId).join(', ') : ''})
                                    </option>
                                ))}
                            </select>
                        </div>
                        <div className="col-md-4">
                            <button className="btn btn-outline-info btn-block" onClick={handleGenerateAudit} disabled={!selectedEnvId || auditing}>
                                <i className={`fas ${auditing ? 'fa-spinner fa-spin' : 'fa-file-signature'} mr-2`}></i>
                                {auditing ? auditStatusText : '전체 인프라 점검표 생성 (Excel)'}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default GcpVmCheckPage;
