import { ReactNode } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import {
  LayoutDashboard, Building2, Users, Mail, CreditCard,
  LogOut, Layers, Network, Send, BookOpen, FileText, Pencil, UserCog
} from 'lucide-react'

function NavItem({ to, icon: Icon, label }: { to: string; icon: React.ElementType; label: string }) {
  return (
    <NavLink to={to} className={({ isActive }) => `nav-item${isActive ? ' active' : ''}`}>
      <Icon size={16} />
      {label}
    </NavLink>
  )
}

export default function Layout({ children }: { children: ReactNode }) {
  const { session, logout, isAdmin, isOrgViewer, canManageMembers } = useAuth()
  const navigate = useNavigate()

  const handleLogout = () => { logout(); navigate('/login') }

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="sidebar-logo">Invoice<span>Direct</span></div>
        <nav className="sidebar-nav">
          <div className="nav-section">Overview</div>
          <NavItem to="/dashboard" icon={LayoutDashboard} label="Dashboard" />

          {isAdmin && (
            <>
              <div className="nav-section">Admin</div>
              <NavItem to="/admin/organizations" icon={Building2} label="Organizations" />
              <NavItem to="/admin/rate-profiles" icon={Layers} label="Rate Profiles" />
              <NavItem to="/admin/billing" icon={CreditCard} label="Billing" />
              <NavItem to="/admin/campaigns" icon={Mail} label="All Campaigns" />
              <NavItem to="/admin/peppol" icon={Network} label="PEPPOL eRegistry" />
            </>
          )}

          {!isAdmin && (
            <>
              <div className="nav-section">My Account</div>
              {!isOrgViewer && <NavItem to="/send" icon={Send} label="Send Invoice" />}
              <NavItem to="/campaigns" icon={Mail} label="Campaigns" />
              <NavItem to="/invoices" icon={FileText} label="Invoices" />
              {!isOrgViewer && <NavItem to="/email-templates" icon={Pencil} label="Email Templates" />}
              <NavItem to="/customers" icon={Users} label="Customers" />
              {canManageMembers && <NavItem to="/team" icon={UserCog} label="Team" />}
            </>
          )}

          <div className="nav-section">Developer</div>
          <NavItem to="/api-docs" icon={BookOpen} label="API Docs" />
        </nav>
        <div className="sidebar-footer">
          <div className="sidebar-org">{session?.name ?? 'Organization'}</div>
          <div className="sidebar-role">
            {isAdmin ? 'Platform Admin' : (isOrgViewer ? 'Viewer (read-only)' : 'Organisation Admin')}
          </div>
          <button className="logout-btn" onClick={handleLogout}>
            <LogOut size={13} style={{ display: 'inline', marginRight: 6 }} />
            Sign out
          </button>
        </div>
      </aside>
      <div className="main">{children}</div>
    </div>
  )
}
