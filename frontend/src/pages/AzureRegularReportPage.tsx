import React, { useEffect, useState } from 'react';
import { getCustomers, getEnvironmentsByCustomer, downloadPptxAuditReportDirectly, InfraCustomer, InfraEnvironment } from '../services/api';

const AzureRegularReportPage: React.FC = () => {
    const [customers, setCustomers] = useState<InfraCustomer[]>([]);
    const [environments, setEnvironments] = useState<InfraEnvironment[]>([]);
    
    const [selectedCustomerId, setSelectedCustomerId] = useState<string>('');
    const [selectedEnvId, setSelectedEnvId] = useState<string>('');
    const [targetDate, setTargetDate] = useState<string>('');
    const [auditing, setAuditing] = useState(false);
    const [auditStatusText, setAuditStatusText] = useState('Azure 정기보고서 생성 (PPTX)');

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

    const handleGeneratePptxAudit = async () => {
        if (!selectedEnvId) return;
        setAuditing(true);
        setAuditStatusText('Azure 정기보고서 작성 중... (최대 수 분 소요)');
        
        try {
            const downloadRes = await downloadPptxAuditReportDirectly(selectedEnvId, targetDate);
            
            const disposition = downloadRes.headers['content-disposition'];
            let filename = `Azure_정기보고서_${new Date().toISOString().split('T')[0]}.pptx`;
            if (disposition && disposition.indexOf('filename=') !== -1) {
                const filenameRegex = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/;
                const matches = filenameRegex.exec(disposition);
                if (matches != null && matches[1]) {
                    filename = decodeURIComponent(matches[1].replace(/['"]/g, ''));
                }
            }

            const blobType = filename.endsWith('.zip')
                ? 'application/zip'
                : 'application/vnd.openxmlformats-officedocument.presentationml.presentation';

            const url = window.URL.createObjectURL(new Blob([downloadRes.data], { type: blobType }));
            const link = document.createElement('a');
            link.href = url;
            link.setAttribute('download', filename);
            document.body.appendChild(link);
            link.click();
            link.remove();
        } catch (e) {
            console.error(e);
            alert('Azure 정기보고서 생성 실패');
        } finally {
            setAuditing(false);
            setAuditStatusText('Azure 정기보고서 생성 (PPTX)');
        }
    };

    return (
        <div className="container-fluid">
            <div className="content-header mb-3">
                <h1 className="m-0 text-light font-weight-bold">
                    <i className="fab fa-microsoft text-primary mr-2"></i>Azure 정기보고서
                </h1>
                <p className="text-muted">Azure 인프라 환경의 월간/분기 정기 보고서를 PPTX 양식으로 자동 작성 및 다운로드합니다.</p>
            </div>

            <div className="card bg-dark border-secondary shadow-sm mb-4">
                <div className="card-header border-secondary">
                    <h5 className="card-title text-light mb-0">보고서 대상 및 기준일 선택</h5>
                </div>
                <div className="card-body">
                    <div className="row align-items-end">
                        <div className="col-md-3 mb-3 mb-md-0">
                            <label className="text-muted">고객사 선택</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedCustomerId} onChange={handleCustomerChange}>
                                <option value="">고객사 선택</option>
                                {customers.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                            </select>
                        </div>
                        <div className="col-md-3 mb-3 mb-md-0">
                            <label className="text-muted">Azure 환경</label>
                            <select className="form-control bg-secondary text-light border-0" value={selectedEnvId} onChange={handleEnvChange} disabled={!selectedCustomerId}>
                                <option value="">환경 선택</option>
                                {environments.map(e => (
                                    <option key={e.id} value={e.id}>
                                        {e.environmentName} {e.azureTenantId ? `(${e.azureTenantId})` : ''}
                                    </option>
                                ))}
                            </select>
                        </div>
                        <div className="col-md-3 mb-3 mb-md-0">
                            <label className="text-muted">기준 일자</label>
                            <input 
                                type="date" 
                                className="form-control bg-secondary text-light border-0" 
                                value={targetDate} 
                                onChange={(e) => setTargetDate(e.target.value)} 
                            />
                        </div>
                        <div className="col-md-3">
                            <button className="btn btn-primary btn-block shadow-sm" onClick={handleGeneratePptxAudit} disabled={!selectedEnvId || auditing}>
                                <i className={`fas ${auditing ? 'fa-spinner fa-spin' : 'fa-file-powerpoint'} mr-2`}></i>
                                {auditing ? auditStatusText : '정기보고서 생성 (PPTX)'}
                            </button>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default AzureRegularReportPage;
