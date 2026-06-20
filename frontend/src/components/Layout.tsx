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

  const roleLabel = isAdmin ? 'Platform Admin' : (isOrgViewer ? 'Viewer (read-only)' : 'Org Admin')

  return (
    <div className="layout">
      <aside className="sidebar">
        <div className="sidebar-logo">Invoice<span>Direct</span></div>
        <nav className="sidebar-nav">
          <div className="nav-section">Overview</div>
          <NavItem to="/dashboard" icon={LayoutDashboard} label="Dashboard" />

          {isAdmin && (
            <>
              <div className="nav-section">Administration</div>
              <NavItem to="/admin/organizations" icon={Building2} label="Organizations" />
              <NavItem to="/admin/rate-profiles" icon={Layers} label="Rate Profiles" />
              <NavItem to="/admin/billing" icon={CreditCard} label="Billing" />
              <NavItem to="/admin/campaigns" icon={Mail} label="All Campaigns" />
              <NavItem to="/admin/peppol" icon={Network} label="PEPPOL Registry" />
            </>
          )}

          {!isAdmin && (
            <>
              <div className="nav-section">Invoicing</div>
              {!isOrgViewer && <NavItem to="/send" icon={Send} label="Send Invoice" />}
              <NavItem to="/invoices" icon={FileText} label="Sent Emails" />
              {!isOrgViewer && <NavItem to="/email-templates" icon={Pencil} label="Email Templates" />}
              <NavItem to="/customers" icon={Users} label="Customers" />
              {canManageMembers && <NavItem to="/team" icon={UserCog} label="Team" />}
            </>
          )}

          <div className="nav-section">Developer</div>
          <NavItem to="/api-docs" icon={BookOpen} label="API Docs" />
        </nav>
        <div className="sidebar-footer">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <div style={{
              width: 32, height: 32, borderRadius: 8,
              background: 'linear-gradient(135deg, var(--brand-orange), var(--brand-orange-light))',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: '#fff', fontWeight: 700, fontSize: 13, flexShrink: 0
            }}>
              {(session?.name ?? 'O').charAt(0).toUpperCase()}
            </div>
            <div style={{ overflow: 'hidden' }}>
              <div className="sidebar-org">{session?.name ?? 'Organization'}</div>
              <div className="sidebar-role">{roleLabel}</div>
            </div>
          </div>
          <button className="logout-btn" onClick={handleLogout}>
            <LogOut size={13} />
            Sign out
          </button>
        </div>
      </aside>
      <div className="main">{children}</div>
    </div>
  )
}
