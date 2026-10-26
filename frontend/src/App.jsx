import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/layout/AppLayout.jsx';
import NotFoundPage from './pages/NotFoundPage.jsx';
import OverviewPage from './pages/OverviewPage.jsx';
import ServiceStatusPage from './pages/ServiceStatusPage.jsx';

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<OverviewPage />} />
        <Route path="status" element={<ServiceStatusPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
      <Route path="/home" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
