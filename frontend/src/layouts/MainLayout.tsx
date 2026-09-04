import React, { useEffect, useState } from 'react';
import { Outlet, Link, useLocation } from 'react-router-dom';
import { getCustomers } from '../services/api';

const MainLayout: React.FC = () => {
  const location = useLocation();
  const [customerCount, setCustomerCount] = useState<number | null>(null);

  useEffect(() => {
    const fetchCustomerCount = async () => {
      try {
        const res = await getCustomers();
        setCustomerCount(res.data.length);
      } catch (e) {
        console.error("Failed to fetch customer count for sidebar", e);
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
      <div className="sidebar-wrapper-panel no-print">
        <Link to="/" className="logo text-decoration-none">
          <i className="fas fa-atom fa-lg mr-2" style={{ color: '#1d8cf8' }}></i>
          <span className="logo-text">Black Dashboard</span>
        </Link>

        <ul className="nav">
          <li className="nav-item">
            <Link to="/" className={getLinkClass('/')}>
              <i className="fas fa-chart-pie"></i>
              <p className="m-0">Dashboard</p>
            </Link>
          </li>

          <li className="nav-header">Operations</li>

          <li className="nav-item">
            <Link to="/management" className={getLinkClass('/management')}>
              <i className="fas fa-users"></i>
              <p className="m-0">Customers {customerCount !== null ? `(${customerCount})` : ''}</p>
            </Link>
          </li>

          <li className="nav-header">Checklists</li>

          <li className="nav-item">
            <Link to="/gcp-checklist" className={getLinkClass('/gcp-checklist')}>
              <i className="fab fa-google"></i>
              <p className="m-0">GCP Checklist</p>
            </Link>
          </li>

          <li className="nav-item">
            <Link to="/azure-checklist" className={getLinkClass('/azure-checklist')}>
              <i className="fab fa-microsoft"></i>
              <p className="m-0">Azure Checklist</p>
            </Link>
          </li>

          <li className="nav-header">Reports (Web / PDF)</li>

          <li className="nav-item">
            <Link to="/gcp-report" className={getLinkClass('/gcp-report')}>
              <i className="fas fa-file-alt"></i>
              <p className="m-0">GCP Report (Web/PDF)</p>
            </Link>
          </li>

          <li className="nav-item">
            <Link to="/azure-report" className={getLinkClass('/azure-report')}>
              <i className="fas fa-file-powerpoint"></i>
              <p className="m-0">Azure Report</p>
            </Link>
          </li>

          <li className="nav-header">Reservations</li>

          <li className="nav-item">
            <Link to="/reservations" className={getLinkClass('/reservations')}>
              <i className="fas fa-calendar-alt"></i>
              <p className="m-0">CUD / RI Status</p>
            </Link>
          </li>
        </ul>
      </div>

      {/* Main Content Panel */}
      <div className="main-panel-content">
        {/* Top Navbar Header */}
        <div className="no-print d-flex justify-content-between align-items-center mb-4 pb-2" style={{ borderBottom: '1px solid rgba(255, 255, 255, 0.05)' }}>
          <div className="d-flex align-items-center">
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
