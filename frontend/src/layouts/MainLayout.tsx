import React, { useEffect, useState } from 'react';
import { Outlet, Link, useLocation } from 'react-router-dom';
import { getCustomers } from '../services/api';

const MainLayout: React.FC = () => {
  const location = useLocation();
  const [customerCount, setCustomerCount] = useState<number | null>(null);

  // 사이드바 열림/미니 모드 상태 관리 (기본값: true, 로컬스토리지 유지)
  const [isSidebarOpen, setIsSidebarOpen] = useState<boolean>(() => {
    try {
      const saved = localStorage.getItem('sidebar_open');
      return saved !== null ? saved === 'true' : true;
    } catch {
      return true;
    }
  });

  // 사이드바 토글 핸들러
  const toggleSidebar = () => {
    setIsSidebarOpen(prev => {
      const next = !prev;
      try {
        localStorage.setItem('sidebar_open', String(next));
      } catch (e) {
        console.warn('Failed to save sidebar state to localStorage', e);
      }
      return next;
    });
  };

  useEffect(() => {
    const fetchCustomerCount = async () => {
      try {
        const res = await getCustomers();
        if (res && res.data && Array.isArray(res.data)) {
          setCustomerCount(res.data.length);
        }
      } catch (e) {
        // 고객사 목록 API 연결 실패 시 사이드바 렌더링에 영향 없도록 안전하게 처리
        console.warn("Failed to fetch customer count for sidebar", e);
      }
    };
    fetchCustomerCount();
  }, [location.pathname]);

  const getLinkClass = (path: string) => {
    const isActive = path === '/'
      ? location.pathname === '/'
      : path === '/management'
      ? location.pathname.startsWith('/management')
      : location.pathname === path;

    return `nav-link ${isActive ? 'active' : ''}`;
  };

  return (
    <>
      {/* Black Dashboard React Floating Sidebar */}
      <div className={`sidebar-wrapper-panel no-print ${isSidebarOpen ? '' : 'mini-sidebar'}`}>
        <div className="logo">
          <Link to="/" className="logo-content" title="Black Dashboard Home">
            <i className="fas fa-atom fa-lg mr-2" style={{ color: '#1d8cf8' }}></i>
            <span className="logo-text">Black Dashboard</span>
          </Link>

          {/* 사이드바 내부 인라인 접기 버튼 */}
          <button
            type="button"
            className="sidebar-toggle-inline-btn"
            onClick={toggleSidebar}
            title="메뉴 접기 (사이드바 축소)"
            aria-label="사이드바 축소"
          >
            <i className="fas fa-chevron-left"></i>
          </button>
        </div>

        <ul className="nav">
          <li className="nav-item">
            <Link to="/" className={getLinkClass('/')} title="Dashboard">
              <i className="fas fa-chart-pie"></i>
              <p className="m-0">Dashboard</p>
            </Link>
          </li>

          <li className="nav-header">Operations</li>

          <li className="nav-item">
            <Link to="/management" className={getLinkClass('/management')} title={`Customers ${customerCount !== null ? `(${customerCount})` : ''}`}>
              <i className="fas fa-users"></i>
              <p className="m-0">Customers {customerCount !== null ? `(${customerCount})` : ''}</p>
            </Link>
          </li>

          <li className="nav-header">Checklists</li>

          <li className="nav-item">
            <Link to="/gcp-checklist" className={getLinkClass('/gcp-checklist')} title="GCP Checklist">
              <i className="fab fa-google"></i>
              <p className="m-0">GCP Checklist</p>
            </Link>
          </li>

          <li className="nav-item">
            <Link to="/azure-checklist" className={getLinkClass('/azure-checklist')} title="Azure Checklist">
              <i className="fab fa-microsoft"></i>
              <p className="m-0">Azure Checklist</p>
            </Link>
          </li>

          <li className="nav-header">Reports (Web / PDF)</li>

          <li className="nav-item">
            <Link to="/gcp-report" className={getLinkClass('/gcp-report')} title="GCP Report (Web/PDF)">
              <i className="fas fa-file-alt"></i>
              <p className="m-0">GCP Report (Web/PDF)</p>
            </Link>
          </li>

          <li className="nav-item">
            <Link to="/azure-report" className={getLinkClass('/azure-report')} title="Azure Report">
              <i className="fas fa-file-powerpoint"></i>
              <p className="m-0">Azure Report</p>
            </Link>
          </li>

          <li className="nav-header">Reservations</li>

          <li className="nav-item">
            <Link to="/reservations" className={getLinkClass('/reservations')} title="CUD / RI Status">
              <i className="fas fa-calendar-alt"></i>
              <p className="m-0">CUD / RI Status</p>
            </Link>
          </li>
        </ul>
      </div>

      {/* Main Content Panel */}
      <div className={`main-panel-content ${isSidebarOpen ? '' : 'expanded-panel'}`}>
        {/* Top Navbar Header */}
        <div className="no-print d-flex justify-content-between align-items-center mb-4 pb-2" style={{ borderBottom: '1px solid rgba(255, 255, 255, 0.05)' }}>
          <div className="d-flex align-items-center">
            {/* Navbar 사이드바 토글 햄버거 버튼 */}
            <button
              type="button"
              id="sidebar-toggle-btn"
              className="header-sidebar-toggle-btn"
              onClick={toggleSidebar}
              title={isSidebarOpen ? "사이드바 메뉴 숨기기 (축소)" : "사이드바 메뉴 펼치기 (확장)"}
              aria-label="사이드바 메뉴 토글"
            >
              <i className={`fas ${isSidebarOpen ? 'fa-bars' : 'fa-indent'}`}></i>
            </button>

            <h4 className="text-white font-weight-300 m-0" style={{ letterSpacing: '0.5px' }}>
              MegazoneCloud Infra Management
            </h4>
          </div>
          <div className="d-flex align-items-center">
            <span className="badge badge-bd-blue px-3 py-2 font-weight-bold mr-3" style={{ borderRadius: '12px', fontSize: '0.8rem' }}>
              <i className="fas fa-layer-group mr-1"></i> MegazoneCloud
            </span>
            <div className="d-flex align-items-center">
              <i className="fas fa-user-circle fa-xl text-light mr-2"></i>
              <span className="text-light small font-weight-bold">Administrator</span>
            </div>
          </div>
        </div>

        {/* Page Content Outlet */}
        <Outlet />

        {/* Footer */}
        <footer className="no-print mt-5 pt-3 text-muted small d-flex justify-content-between align-items-center" style={{ borderTop: '1px solid rgba(255, 255, 255, 0.05)' }}>
          <div>
            © 2026 <strong>MegazoneCloud Cloud Infra Admin</strong> - Black Dashboard React Theme
          </div>
          <div>
            <span className="badge badge-bd-teal px-2 py-1">Engine Active</span>
          </div>
        </footer>
      </div>
    </>
  );
};

export default MainLayout;
