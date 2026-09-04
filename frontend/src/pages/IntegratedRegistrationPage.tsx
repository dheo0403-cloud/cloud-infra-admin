import React, { useState, useEffect } from 'react';
import { saveCustomer, getCustomer, InfraCustomer, InfraEnvironment, CloudProject } from '../services/api';
import { useNavigate, useParams } from 'react-router-dom';

const IntegratedRegistrationPage: React.FC = () => {
    const { id } = useParams<{ id: string }>();
    const navigate = useNavigate();
    const [loading, setLoading] = useState(false);
    const [isEditMode, setIsEditMode] = useState(false);

    // Customer Form States
    const [customerName, setCustomerName] = useState('');
    const [contactPerson, setContactPerson] = useState('');
    const [contactEmail, setContactEmail] = useState('');
    const [reportFrequency, setReportFrequency] = useState('QUARTERLY');
    const [mspGrade, setMspGrade] = useState('Standard');
    const [mspSalesRep, setMspSalesRep] = useState('');
    const [mspRep, setMspRep] = useState('');
    const [jiraProjectKeyGcp, setJiraProjectKeyGcp] = useState('');
    const [jiraProjectKeyAzure, setJiraProjectKeyAzure] = useState('');

    // Environments State
    const [environments, setEnvironments] = useState<InfraEnvironment[]>([]);

    useEffect(() => {
        if (id) {
            setIsEditMode(true);
            loadCustomerData(id);
        } else {
            // Initial empty environment for new registration
            addEnvironment();
        }
    }, [id]);

    const loadCustomerData = async (cid: string) => {
        setLoading(true);
        try {
            const res = await getCustomer(cid);
            const customer = res.data;
            setCustomerName(customer.name);
            setContactPerson(customer.contactPerson);
            setContactEmail(customer.contactEmail);
            setReportFrequency(customer.reportFrequency || 'QUARTERLY');
            setMspGrade(customer.mspGrade || 'Standard');
            setMspSalesRep(customer.mspSalesRep || '');
            setMspRep(customer.mspRep || '');
            setJiraProjectKeyGcp(customer.jiraProjectKeyGcp || '');
            setJiraProjectKeyAzure(customer.jiraProjectKeyAzure || '');
            if (customer.environments && customer.environments.length > 0) {
                setEnvironments(customer.environments);
            } else {
                addEnvironment();
            }
        } catch (error) {
            console.error(error);
            alert('고객사 정보를 불러오는데 실패했습니다.');
        } finally {
            setLoading(false);
        }
    };

    const addEnvironment = () => {
        const newEnv: InfraEnvironment = {
            providerType: 'GCP',
            environmentName: 'Production',
            projects: [{ projectId: '' }],
            encryptedSecret: ''
        };
        setEnvironments(prev => [...prev, newEnv]);
    };

    const removeEnvironment = (index: number) => {
        setEnvironments(prev => prev.filter((_, i) => i !== index));
    };

    const handleEnvChange = async (index: number, field: keyof InfraEnvironment, value: any) => {
        if (field === 'providerType') {
            const currentEnv = environments[index];
            if (currentEnv && currentEnv.providerType !== value) {
                // Attempt to restore from DB if it previously existed
                if (currentEnv.id && id) {
                    try {
                        const res = await getCustomer(id);
                        const dbCustomer = res.data;
                        const dbEnv = dbCustomer.environments?.find(e => e.id === currentEnv.id);
                        if (dbEnv && dbEnv.providerType === value) {
                            if (dbEnv.encryptedSecret && dbEnv.encryptedSecret !== '') {
                                dbEnv.encryptedSecret = '********';
                            }
                            setEnvironments(prev => prev.map((e, i) => i === index ? dbEnv : e));
                            return;
                        }
                    } catch (error) {
                        console.error('Failed to fetch original customer data', error);
                    }
                }

                // Fallback: reset fields for a clean slate
                setEnvironments(prev => prev.map((env, i) => {
                    if (i !== index) return env;
                    return {
                        ...env,
                        [field]: value,
                        projects: [{ projectId: '' }],
                        encryptedSecret: '',
                        azureTenantId: '',
                        azureClientId: ''
                    };
                }));
                return;
            }
        }

        // Standard update
        setEnvironments(prev => prev.map((env, i) => 
            i === index ? { ...env, [field]: value } : env
        ));
    };

    const addProject = (envIndex: number) => {
        setEnvironments(prev => prev.map((env, i) => {
            if (i !== envIndex) return env;
            return {
                ...env,
                projects: [...env.projects, { projectId: '' }]
            };
        }));
    };

    const removeProject = (envIndex: number, projectIndex: number) => {
        setEnvironments(prev => prev.map((env, i) => {
            if (i !== envIndex) return env;
            return {
                ...env,
                projects: env.projects.filter((_, pi) => pi !== projectIndex)
            };
        }));
    };

    const handleProjectChange = (envIndex: number, projectIndex: number, value: string) => {
        setEnvironments(prev => prev.map((env, i) => {
            if (i !== envIndex) return env;
            const newProjects = env.projects.map((proj, pi) => 
                pi === projectIndex ? { ...proj, projectId: value } : proj
            );
            return { ...env, projects: newProjects };
        }));
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setLoading(true);

        // Validation
        if (environments.length === 0) {
            alert('최소 하나 이상의 클라우드 환경이 등록되어야 합니다.');
            setLoading(false);
            return;
        }

        const processedEnvironments = environments.map(env => {
            if (env.providerType === 'GCP') {
                const firstProjectId = env.projects && env.projects[0]?.projectId;
                return {
                    ...env,
                    environmentName: firstProjectId || 'GCP Environment'
                };
            }
            return env;
        });

        const payload: InfraCustomer = {
            id: isEditMode ? id : undefined,
            name: customerName,
            contactPerson,
            contactEmail,
            reportFrequency,
            mspGrade,
            mspSalesRep,
            mspRep,
            jiraProjectKeyGcp,
            jiraProjectKeyAzure,
            environments: processedEnvironments
        };

        try {
            await saveCustomer(payload);
            alert(isEditMode ? '정보가 성공적으로 수정되었습니다.' : '고객사 및 환경 정보가 성공적으로 등록되었습니다.');
            navigate('/management');
        } catch (error) {
            console.error(error);
            alert('저장 중 오류가 발생했습니다.');
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="container-fluid pb-5">
            <div className="content-header">
                <div className="d-flex align-items-center">
                    <button className="btn btn-outline-light mr-3" onClick={() => navigate('/management')}>
                        <i className="fas fa-arrow-left"></i>
                    </button>
                    <h1 className="m-0 text-light">{isEditMode ? '고객사 정보 수정' : '신규 고객 및 클라우드 환경 등록'}</h1>
                </div>
            </div>

            <form onSubmit={handleSubmit}>
                <div className="row">
                    {/* Customer Information */}
                    <div className="col-12 mb-4">
                        <div className="card card-primary card-outline bg-dark shadow">
                            <div className="card-header">
                                <h3 className="card-title font-weight-bold"><i className="fas fa-user-tie mr-2"></i>고객사 기본 정보</h3>
                            </div>
                            <div className="card-body">
                                <div className="row">
                                    <div className="col-md-3">
                                        <div className="form-group">
                                            <label>고객사명 <span className="text-danger">*</span></label>
                                            <input type="text" className="form-control bg-secondary text-light border-0" 
                                                value={customerName} onChange={e => setCustomerName(e.target.value)} required placeholder="회사명 입력" />
                                        </div>
                                    </div>
                                    <div className="col-md-3">
                                        <div className="form-group">
                                            <label>담당자명</label>
                                            <input type="text" className="form-control bg-secondary text-light border-0" 
                                                value={contactPerson} onChange={e => setContactPerson(e.target.value)} placeholder="담당자 성함" />
                                        </div>
                                    </div>
                                    <div className="col-md-3">
                                        <div className="form-group">
                                            <label>이메일</label>
                                            <input type="email" className="form-control bg-secondary text-light border-0" 
                                                value={contactEmail} onChange={e => setContactEmail(e.target.value)} placeholder="contact@example.com" />
                                        </div>
                                    </div>
                                    <div className="col-md-3">
                                        <div className="form-group">
                                            <label>보고서 발행 주기 <span className="text-danger">*</span></label>
                                            <select className="form-control bg-secondary text-light border-0" 
                                                value={reportFrequency} onChange={e => setReportFrequency(e.target.value)} required>
                                                <option value="MONTHLY">월간 (Monthly)</option>
                                                <option value="QUARTERLY">분기 (Quarterly)</option>
                                            </select>
                                        </div>
                                    </div>
                                </div>
                                <div className="row mt-3">
                                    <div className="col-md-4">
                                        <div className="form-group">
                                            <label>MSP 등급</label>
                                            <select className="form-control bg-secondary text-light border-0" 
                                                value={mspGrade} onChange={e => setMspGrade(e.target.value)}>
                                                <option value="Economy">Economy</option>
                                                <option value="Basic">Basic</option>
                                                <option value="Standard">Standard</option>
                                                <option value="Premium">Premium</option>
                                            </select>
                                        </div>
                                    </div>
                                    <div className="col-md-4">
                                        <div className="form-group">
                                            <label>MSP 영업 담당자</label>
                                            <input type="text" className="form-control bg-secondary text-light border-0" 
                                                value={mspSalesRep} onChange={e => setMspSalesRep(e.target.value)} placeholder="영업 담당자명 입력" />
                                        </div>
                                    </div>
                                    <div className="col-md-4">
                                        <div className="form-group">
                                            <label>MSP 담당자</label>
                                            <input type="text" className="form-control bg-secondary text-light border-0" 
                                                value={mspRep} onChange={e => setMspRep(e.target.value)} placeholder="담당자명 입력" />
                                        </div>
                                    </div>
                                    <div className="col-md-4">
                                        <div className="form-group">
                                            <label><i className="fab fa-google mr-1 text-warning"></i>GCP Jira 프로젝트 키</label>
                                            <input type="text" className="form-control bg-secondary text-light border-0" 
                                                value={jiraProjectKeyGcp} onChange={e => setJiraProjectKeyGcp(e.target.value.toUpperCase())} placeholder="예: HCOMPANY_GCP" />
                                        </div>
                                    </div>
                                    <div className="col-md-4">
                                        <div className="form-group">
                                            <label><i className="fab fa-microsoft mr-1 text-info"></i>Azure Jira 프로젝트 키</label>
                                            <input type="text" className="form-control bg-secondary text-light border-0" 
                                                value={jiraProjectKeyAzure} onChange={e => setJiraProjectKeyAzure(e.target.value.toUpperCase())} placeholder="예: HCOMPANY_AZURE" />
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Environments Sections */}
                    <div className="col-12 mb-4">
                        <div className="d-flex justify-content-between align-items-center mb-3">
                            <h4 className="text-light m-0"><i className="fas fa-cloud mr-2"></i>클라우드 환경 정보</h4>
                            <button type="button" className="btn btn-info btn-sm" onClick={addEnvironment}>
                                <i className="fas fa-plus mr-1"></i>환경 추가
                            </button>
                        </div>

                        {environments.map((env, envIdx) => (
                            <div key={envIdx} className="card card-outline card-info bg-dark mb-4 shadow position-relative">
                                <div className="card-header d-flex justify-content-between align-items-center">
                                    <h3 className="card-title">환경 #{envIdx + 1} ({env.providerType})</h3>
                                    {environments.length > 1 && (
                                        <button type="button" className="btn btn-outline-danger btn-xs" onClick={() => removeEnvironment(envIdx)}>
                                            <i className="fas fa-times mr-1"></i>삭제
                                        </button>
                                    )}
                                </div>
                                <div className="card-body">
                                    <div className="row">
                                        <div className="col-md-3">
                                            <div className="form-group">
                                                <label>제공업체 (CSP)</label>
                                                <select className="form-control bg-secondary text-light border-0" 
                                                    value={env.providerType} onChange={e => handleEnvChange(envIdx, 'providerType', e.target.value)}>
                                                    <option value="GCP">Google Cloud Platform (GCP)</option>
                                                    <option value="AZURE">Microsoft Azure</option>
                                                </select>
                                            </div>
                                        </div>
                                        {env.providerType === 'AZURE' && (
                                            <div className="col-md-3">
                                                <div className="form-group">
                                                    <label>환경 명칭 <span className="text-danger">*</span></label>
                                                    <input type="text" className="form-control bg-secondary text-light border-0" 
                                                        value={env.environmentName} onChange={e => handleEnvChange(envIdx, 'environmentName', e.target.value)} required />
                                                </div>
                                            </div>
                                        )}
                                        {env.providerType === 'AZURE' && (
                                            <>
                                                <div className="col-md-3">
                                                    <div className="form-group">
                                                        <label>Azure Tenant ID</label>
                                                        <input type="text" className="form-control bg-secondary text-light border-0" 
                                                            value={env.azureTenantId || ''} onChange={e => handleEnvChange(envIdx, 'azureTenantId', e.target.value)} required />
                                                    </div>
                                                </div>
                                                <div className="col-md-3">
                                                    <div className="form-group">
                                                        <label>Azure Client ID</label>
                                                        <input type="text" className="form-control bg-secondary text-light border-0" 
                                                            value={env.azureClientId || ''} onChange={e => handleEnvChange(envIdx, 'azureClientId', e.target.value)} required />
                                                    </div>
                                                </div>
                                            </>
                                        )}
                                    </div>

                                    {/* Project IDs list */}
                                    <div className="form-group">
                                        <div className="d-flex align-items-center mb-2">
                                            <label className="m-0 mr-2">{env.providerType === 'GCP' ? 'GCP Project IDs' : 'Azure Subscription IDs'}</label>
                                            <button type="button" className="btn btn-outline-info btn-xs" onClick={() => addProject(envIdx)}>
                                                <i className="fas fa-plus"></i>
                                            </button>
                                        </div>
                                        <div className="row">
                                            {env.projects?.map((proj, projIdx) => (
                                                <div key={projIdx} className="col-md-4 mb-2">
                                                    <div className="input-group">
                                                        <input type="text" className="form-control bg-secondary text-light border-0" 
                                                            value={proj.projectId} onChange={e => handleProjectChange(envIdx, projIdx, e.target.value)} 
                                                            required placeholder="ID 입력" />
                                                        {env.projects.length > 1 && (
                                                            <div className="input-group-append">
                                                                <button className="btn btn-outline-danger btn-sm border-0" type="button" onClick={() => removeProject(envIdx, projIdx)}>
                                                                    <i className="fas fa-trash"></i>
                                                                </button>
                                                            </div>
                                                        )}
                                                    </div>
                                                </div>
                                            ))}
                                        </div>
                                    </div>

                                    <div className="form-group">
                                        <label>{env.providerType === 'GCP' ? 'GCP Service Account Key (JSON)' : 'Azure Client Secret'}</label>
                                        <textarea className="form-control bg-secondary text-light border-0" rows={3} 
                                            value={env.encryptedSecret} onChange={e => handleEnvChange(envIdx, 'encryptedSecret', e.target.value)} 
                                            required={!isEditMode || (env.encryptedSecret !== '********')}
                                            placeholder={env.providerType === 'GCP' ? 'JSON 내용을 붙여넣으세요' : 'Client Secret을 입력하세요'} />
                                        {isEditMode && <small className="text-muted">보안을 위해 기존 키는 마스킹 처리되었습니다. 변경 시에만 다시 입력하세요.</small>}
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>

                <div className="row mt-4 mb-5">
                    <div className="col-12 text-center">
                        <button type="submit" className="btn btn-lg btn-success px-5 py-3 shadow" disabled={loading}>
                            {loading ? <i className="fas fa-spinner fa-spin mr-2"></i> : <i className="fas fa-save mr-2"></i>}
                            {isEditMode ? '정보 수정 완료' : '전체 정보 저장 및 등록'}
                        </button>
                        <button type="button" className="btn btn-lg btn-outline-light ml-3 px-5 py-3 shadow" onClick={() => navigate('/management')}>취소</button>
                    </div>
                </div>
            </form>
        </div>
    );
};

export default IntegratedRegistrationPage;
