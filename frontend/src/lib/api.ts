import axios, { type AxiosError } from 'axios'

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,       // send HttpOnly cookies automatically
  headers: { 'Content-Type': 'application/json' },
})

let isRefreshing = false
let refreshSubscribers: Array<() => void> = []

function subscribeToRefresh(cb: () => void) {
  refreshSubscribers.push(cb)
}

function notifyRefreshSubscribers() {
  refreshSubscribers.forEach(cb => cb())
  refreshSubscribers = []
}

// Response interceptor: auto-refresh on 401
api.interceptors.response.use(
  res => res,
  async (error: AxiosError) => {
    const originalRequest = error.config as typeof error.config & { _retry?: boolean }

    if (
      error.response?.status === 401 &&
      !originalRequest._retry &&
      !originalRequest.url?.includes('/auth/')
    ) {
      if (isRefreshing) {
        return new Promise(resolve => {
          subscribeToRefresh(() => resolve(api(originalRequest!)))
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        await api.post('/auth/refresh')
        notifyRefreshSubscribers()
        return api(originalRequest!)
      } catch {
        // Refresh failed — redirect to login
        window.location.href = '/login?redirect=' + encodeURIComponent(window.location.pathname + window.location.search)
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    }

    return Promise.reject(error)
  }
)

// ─── Typed API functions ──────────────────────────────────────────────────

export interface Account {
  id: number
  name: string
  type: AccountType
  provider: string | null
  currency: string
  currentBalance: number
  currentBalanceEur: number
  lastSyncedAt: string | null
  isManual: boolean
  color: string
  ticker: string | null
  createdAt: string
}

export type AccountType =
  | 'LEP' | 'PEA' | 'COMPTE_TITRES' | 'CRYPTO' | 'CHECKING' | 'SAVINGS' | 'OTHER'

export interface AccountRequest {
  name: string
  type: AccountType
  provider?: string
  currency: string
  currentBalance?: number
  isManual: boolean
  color?: string
  ticker?: string
}

export interface BalanceSnapshot {
  id: number
  date: string
  balance: number
}

export interface GoalProgress {
  id: number
  name: string
  targetAmount: number
  deadline: string
  accounts: Account[]
  currentTotal: number
  percentComplete: number
  monthsLeft: number
  monthlyNeeded: number
  avgMonthlyContribution: number | null
  isOnTrack: boolean
  surplus: number
}

export interface GoalRequest {
  name: string
  targetAmount: number
  deadline: string
  accountIds: number[]
}

export interface DashboardData {
  totalNetWorth: number
  netWorthHistory: { date: string; total: number }[]
  distribution: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
  }[]
  goalSummaries: GoalProgress[]
}

export interface Institution {
  id: string
  name: string
  bic: string | null
  logoUrl: string | null
  country: string
}

// Accounts
export const accountsApi = {
  list: () => api.get<Account[]>('/accounts').then(r => r.data),
  get: (id: number) => api.get<Account>(`/accounts/${id}`).then(r => r.data),
  create: (data: AccountRequest) => api.post<Account>('/accounts', data).then(r => r.data),
  update: (id: number, data: AccountRequest) => api.put<Account>(`/accounts/${id}`, data).then(r => r.data),
  delete: (id: number) => api.delete(`/accounts/${id}`),
  history: (id: number, from?: string, to?: string) =>
    api.get<BalanceSnapshot[]>(`/accounts/${id}/history`, { params: { from, to } }).then(r => r.data),
  addSnapshot: (id: number, balance: number, date: string) =>
    api.post<BalanceSnapshot>(`/accounts/${id}/snapshot`, { balance, date }).then(r => r.data),
}

// Goals
export const goalsApi = {
  list: () => api.get<GoalProgress[]>('/goals').then(r => r.data),
  create: (data: GoalRequest) => api.post<GoalProgress>('/goals', data).then(r => r.data),
  update: (id: number, data: GoalRequest) => api.put<GoalProgress>(`/goals/${id}`, data).then(r => r.data),
  delete: (id: number) => api.delete(`/goals/${id}`),
}

// Dashboard
export const dashboardApi = {
  get: () => api.get<DashboardData>('/dashboard').then(r => r.data),
}

// Sync
export const syncApi = {
  searchInstitutions: (query: string, country?: string) =>
    api.get<Institution[]>('/sync/institutions', { params: { query, country } }).then(r => r.data),
  initiate: (institutionId: string, institutionName: string) =>
    api.post<{ requisitionId: string; authLink: string }>('/sync/initiate', {
      institutionId,
      institutionName,
    }).then(r => r.data),
  complete: (code: string) =>
    api.get<Account[]>('/sync/complete', { params: { code } }).then(r => r.data),
  status: () =>
    api.get<{ id: number; requisitionId: string; institutionName: string; status: string; createdAt: string }[]>(
      '/sync/status'
    ).then(r => r.data),
  retry: (id: number) => api.post<Account[]>(`/sync/${id}/retry`).then(r => r.data),
  delete: (id: number) => api.delete(`/sync/${id}`),
}

// Auth
export const authApi = {
  login: (username: string, password: string) =>
    api.post<{ username: string }>('/auth/login', { username, password }).then(r => r.data),
  logout: () => api.post('/auth/logout'),
  refresh: () => api.post<{ username: string }>('/auth/refresh').then(r => r.data),
}

// Crypto Exchange
export type ExchangeType = 'BINANCE' | 'KRAKEN'

export interface ExchangeStatus {
  id: number
  exchangeType: ExchangeType
  status: string
  lastSyncedAt: string | null
}

export const cryptoExchangeApi = {
  add: (type: ExchangeType, apiKey: string, apiSecret: string) =>
    api.post<Account>('/crypto/exchange', { type, apiKey, apiSecret }).then(r => r.data),
  sync: (id: number) =>
    api.post<Account>(`/crypto/exchange/${id}/sync`).then(r => r.data),
  status: () =>
    api.get<ExchangeStatus[]>('/crypto/exchange/status').then(r => r.data),
  remove: (id: number) =>
    api.delete(`/crypto/exchange/${id}`),
}

// Crypto Wallet
export type ChainType = 'SOLANA' | 'ETHEREUM' | 'BITCOIN'

export interface WalletStatus {
  id: number
  chain: ChainType
  address: string
  label: string | null
  lastSyncedAt: string | null
}

export const cryptoWalletApi = {
  add: (chain: ChainType, address: string, label?: string) =>
    api.post<Account>('/crypto/wallet', { chain, address, label }).then(r => r.data),
  sync: (id: number) =>
    api.post<Account>(`/crypto/wallet/${id}/sync`).then(r => r.data),
  list: () =>
    api.get<WalletStatus[]>('/crypto/wallet').then(r => r.data),
  remove: (id: number) =>
    api.delete(`/crypto/wallet/${id}`),
}

// Trade Republic
export interface TrSessionStatus {
  isActive: boolean
  expiresAt: string | null
}

export const trApi = {
  initiateAuth: (phoneNumber: string, pin: string) =>
    api.post<{ processId: string }>('/tr/auth/initiate', { phoneNumber, pin }).then(r => r.data),
  completeAuth: (processId: string, tan: string) =>
    api.post<Account[]>('/tr/auth/complete', { processId, tan }).then(r => r.data),
  sync: () =>
    api.post<Account[]>('/tr/sync').then(r => r.data),
  status: () =>
    api.get<TrSessionStatus>('/tr/status').then(r => r.data),
  importCsv: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<Account[]>('/tr/import', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data)
  },
  clearSession: () => api.delete('/tr/session'),
}
