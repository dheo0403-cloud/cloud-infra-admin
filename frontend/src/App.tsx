import React from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import MainLayout from './layouts/MainLayout';
import DashboardPage from './pages/DashboardPage';
import GcpVmCheckPage from './pages/GcpVmCheckPage';
import AzureVmCheckPage from './pages/AzureVmCheckPage';
import IntegratedRegistrationPage from './pages/IntegratedRegistrationPage';
import IntegratedManagementListPage from './pages/IntegratedManagementListPage';
import GcpMonthlyReportViewPage from './pages/GcpMonthlyReportViewPage';
import AzureRegularReportPage from './pages/AzureRegularReportPage';
import ReservationPage from './pages/ReservationPage';

const App: React.FC = () => {
    return (
        <Router basename="/">
            <Routes>
                <Route path="/" element={<MainLayout />}>
                    <Route index element={<DashboardPage />} />
                    <Route path="management" element={<IntegratedManagementListPage />} />
                    <Route path="management/register" element={<IntegratedRegistrationPage />} />
                    <Route path="management/edit/:id" element={<IntegratedRegistrationPage />} />
                    
                    <Route path="gcp-checklist" element={<GcpVmCheckPage />} />
                    <Route path="azure-checklist" element={<AzureVmCheckPage />} />
                    <Route path="gcp-report" element={<GcpMonthlyReportViewPage />} />
                    <Route path="azure-report" element={<AzureRegularReportPage />} />
                    <Route path="reservations" element={<ReservationPage />} />
                </Route>
            </Routes>
        </Router>
    );
};

export default App;
