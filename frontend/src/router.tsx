import { createBrowserRouter, Navigate, useLocation } from 'react-router-dom'
import { Layout } from './components/layout'
import { LoginPage } from './pages/login/LoginPage'
import { DashboardPage } from './pages/dashboard/DashboardPage'
import { AccountsPage } from './pages/accounts/AccountsPage'
import { AccountDetailPage } from './pages/accounts/AccountDetailPage'
import { GoalsPage } from './pages/goals/GoalsPage'
import { SyncPage } from './pages/sync/SyncPage'
import { TrSyncPage } from './pages/sync/TrSyncPage'
import { CryptoExchangeSyncPage } from './pages/sync/CryptoExchangeSyncPage'
import { WalletSyncPage } from './pages/sync/WalletSyncPage'
import { SettingsPage } from './pages/settings/SettingsPage'

function isAuthenticated(): boolean {
  return !!sessionStorage.getItem('picsou_user')
}

function RequireAuth({ children }: { children: React.ReactNode }) {
  const location = useLocation()
  if (!isAuthenticated()) {
    const redirect = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?redirect=${redirect}`} replace />
  }
  return <>{children}</>
}

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <Layout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'accounts', element: <AccountsPage /> },
      { path: 'accounts/:id', element: <AccountDetailPage /> },
      { path: 'goals', element: <GoalsPage /> },
      { path: 'sync', element: <SyncPage /> },
      { path: 'sync/callback', element: <SyncPage /> },
      { path: 'sync/trade-republic', element: <TrSyncPage /> },
      { path: 'sync/crypto-exchange', element: <CryptoExchangeSyncPage /> },
      { path: 'sync/crypto-wallet', element: <WalletSyncPage /> },
      { path: 'settings', element: <SettingsPage /> },
    ],
  },
  {
    path: '*',
    element: <Navigate to="/" replace />,
  },
])
