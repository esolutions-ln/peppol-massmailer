import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './context/AuthContext'
import Layout from './components/Layout'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import DashboardPage from './pages/DashboardPage'
import CampaignsPage from './pages/CampaignsPage'
import InvoicesPage from './pages/InvoicesPage'
import CustomersPage from './pages/CustomersPage'
import BillingPage from './pages/BillingPage'
import OrganizationsPage from './pages/admin/OrganizationsPage'
import RateProfilesPage from './pages/admin/RateProfilesPage'
import AdminCampaignsPage from './pages/admin/AdminCampaignsPage'
import AdminLoginPage from './pages/admin/AdminLoginPage'
import PeppolPage from './pages/admin/PeppolPage'
import PeppolInvitePage from './pages/PeppolInvitePage'
import SendTestPage from './pages/SendTestPage'
import ApiDocsPage from './pages/ApiDocsPage'
import { ReactNode } from 'react'

function ProtectedRoute({ children, adminOnly = false }: { children: ReactNode; adminOnly?: boolean }) {
  const { session, isAdmin } = useAuth()
  if (!session) return <Navigate to="/login" replace />
  if (adminOnly && !isAdmin) return <Navigate to="/dashboard" replace />
  return <Layout>{children}</Layout>
}

function AppRoutes() {
  const { session } = useAuth()
  return (
    <Routes>
      <Route path="/invite/peppol/:token" element={<PeppolInvitePage />} />
      <Route path="/login" element={session ? <Navigate to="/dashboard" /> : <LoginPage />} />
      <Route path="/register" element={session ? <Navigate to="/dashboard" /> : <RegisterPage />} />
      <Route path="/admin/login" element={session ? <Navigate to="/dashboard" /> : <AdminLoginPage />} />

      <Route path="/dashboard" element={<ProtectedRoute><DashboardPage /></ProtectedRoute>} />
      <Route path="/campaigns" element={<ProtectedRoute><CampaignsPage /></ProtectedRoute>} />
      <Route path="/invoices" element={<ProtectedRoute><InvoicesPage /></ProtectedRoute>} />
      <Route path="/send" element={<ProtectedRoute><SendTestPage /></ProtectedRoute>} />
      <Route path="/customers" element={<ProtectedRoute><CustomersPage /></ProtectedRoute>} />
      <Route path="/api-docs" element={<ProtectedRoute><ApiDocsPage /></ProtectedRoute>} />

      <Route path="/admin/organizations" element={<ProtectedRoute adminOnly><OrganizationsPage /></ProtectedRoute>} />
      <Route path="/admin/rate-profiles" element={<ProtectedRoute adminOnly><RateProfilesPage /></ProtectedRoute>} />
      <Route path="/admin/billing" element={<ProtectedRoute adminOnly><BillingPage /></ProtectedRoute>} />
      <Route path="/admin/campaigns" element={<ProtectedRoute adminOnly><AdminCampaignsPage /></ProtectedRoute>} />
      <Route path="/admin/peppol" element={<ProtectedRoute adminOnly><PeppolPage /></ProtectedRoute>} />

      <Route path="/billing" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={<Navigate to={session ? '/dashboard' : '/login'} replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  )
}
