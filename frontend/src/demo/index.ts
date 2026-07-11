import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type { GoalProgress } from '@/types/api'
import { mockAccounts } from './data/accounts'
import { mockDashboard } from './data/dashboard'
import { mockGoals } from './data/goals'
import { mockHoldings } from './data/holdings'
import { mockTransactions } from './data/transactions'
import { mockExchangeStatuses, mockWalletStatuses, mockRequisitions } from './data/sync-status'

function randomDelay(): number {
  return 200 + Math.random() * 400
}

type MockHandler = (config: InternalAxiosRequestConfig) => unknown

const handlers = new Map<string, MockHandler>()

function key(method: string, url: string): string {
  const normalized = url.split('?')[0].replace(/\/$/, '')
  return `${method.toUpperCase()} ${normalized}`
}

// Auth
handlers.set(key('POST', '/auth/login'), () => ({ username: 'demo' }))
handlers.set(key('POST', '/auth/refresh'), () => ({ username: 'demo' }))

// Dashboard
handlers.set(key('GET', '/dashboard'), () => mockDashboard)

// Accounts
handlers.set(key('GET', '/accounts'), () => mockAccounts)
for (let i = 1; i <= 7; i++) {
  handlers.set(key('GET', `/accounts/${i}`), () => mockAccounts[i - 1])
}

// Account CRUD
handlers.set(key('POST', '/accounts'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    id: Date.now(),
    name: body.name ?? 'New Account',
    type: body.type ?? 'CHECKING',
    provider: body.provider ?? null,
    currency: body.currency ?? 'EUR',
    currentBalance: body.currentBalance ?? 0,
    currentBalanceEur: body.currentBalance ?? 0,
    lastSyncedAt: null,
    isManual: body.isManual ?? true,
    color: body.color ?? '#6366f1',
    ticker: body.ticker ?? null,
    createdAt: new Date().toISOString(),
  }
})
handlers.set(key('PUT', '/accounts/1'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return { ...mockAccounts[0], ...body }
})
handlers.set(key('DELETE', '/accounts/1'), () => ({}))

// Account details: holdings for PEA (id=2), Compte Titres (id=3), Crypto (id=6)
handlers.set(key('GET', '/accounts/2/holdings'), () => mockHoldings[2] ?? [])
handlers.set(key('GET', '/accounts/3/holdings'), () => mockHoldings[3] ?? [])
handlers.set(key('GET', '/accounts/6/holdings'), () => mockHoldings[6] ?? [])

// Account details: transactions for all accounts
for (let i = 1; i <= 7; i++) {
  handlers.set(key('GET', `/accounts/${i}/transactions`), () => mockTransactions[i] ?? [])
}

// Realized P&L on closed positions. PEA (id=2) shows a green + a red closed lot;
// the other holding accounts report nothing realized yet.
handlers.set(key('GET', '/accounts/2/realized-pnl'), () => ({
  currency: 'EUR',
  realizedTotal: 420.5,
  byTicker: [
    { ticker: 'AAPL', name: 'Apple Inc.', realized: 512, quantitySold: 8, proceeds: 1512, costBasis: 1000, warning: false },
    { ticker: 'TSLA', name: 'Tesla Inc.', realized: -91.5, quantitySold: 3, proceeds: 660, costBasis: 751.5, warning: false },
  ],
  lots: [
    { ticker: 'AAPL', name: 'Apple Inc.', date: '2024-05-14', quantity: 8, avgCost: 125, proceeds: 1512, realized: 512 },
    { ticker: 'TSLA', name: 'Tesla Inc.', date: '2024-09-02', quantity: 3, avgCost: 250.5, proceeds: 660, realized: -91.5 },
  ],
}))
for (const i of [3, 6]) {
  handlers.set(key('GET', `/accounts/${i}/realized-pnl`), () => ({
    currency: 'EUR', realizedTotal: 0, byTicker: [], lots: [],
  }))
}

// CSV transaction import wizard (holding accounts). Preview returns a French-style
// sample (semicolon delimiter, comma decimals); execute reports a canned result.
for (const i of [2, 3, 6]) {
  handlers.set(key('POST', `/accounts/${i}/transactions/import/preview`), () => ({
    fileToken: 'demo-token',
    detectedColumns: ['Date', 'Sens', 'ISIN', 'Quantité', 'Cours', 'Frais'],
    sampleRows: [
      ['15/01/2024', 'Achat', 'IE00B4L5Y983', '10', '85,20', '1,00'],
      ['02/06/2024', 'Vente', 'IE00B4L5Y983', '10', '92,50', '1,00'],
    ],
    totalRows: 2,
    hasHeaderRow: true,
    dialect: { delimiter: ';', decimal: 'COMMA', dateFormat: 'dd/MM/yyyy' },
    suggestedMapping: { date: 0, side: 1, tickerOrIsin: 2, name: null, quantity: 3, unitPrice: 4, fees: 5, currency: null, amount: null },
  }))
  handlers.set(key('POST', `/accounts/${i}/transactions/import`), () => ({
    imported: 2, skipped: 0, errors: [],
  }))
}

// Security insight (asset type + ETF composition). Mirrors the backend
// SecurityInsightResponse: { ticker, assetType, composition | null }.
const demoStockTickers = ['AAPL', 'MSFT', 'AMZN', 'NVDA']
const demoCryptoTickers = ['BTC', 'ETH', 'SOL']
const demoEtfCompositions: Record<string, { companies: [string, number][]; countries: [string, number][]; sectors: [string, number][] }> = {
  IWDA: {
    companies: [['Apple', 5.1], ['Microsoft', 4.4], ['Nvidia', 4.0], ['Amazon', 2.7], ['Meta Platforms', 1.9], ['Alphabet A', 1.7], ['Alphabet C', 1.5], ['Broadcom', 1.3], ['Eli Lilly', 0.9], ['JPMorgan Chase', 0.8]],
    countries: [['US', 70.8], ['JP', 6.0], ['GB', 3.7], ['FR', 3.1], ['CA', 3.0], ['CH', 2.6], ['DE', 2.3], ['AU', 1.8]],
    sectors: [['technology', 24.1], ['financial_services', 16.4], ['healthcare', 11.2], ['industrials', 10.7], ['consumer_cyclical', 10.2], ['communication_services', 7.6], ['consumer_defensive', 6.1], ['energy', 4.0], ['basic_materials', 3.6], ['utilities', 2.7]],
  },
  EUNL: {
    companies: [['Apple', 7.1], ['Microsoft', 6.6], ['Nvidia', 6.1], ['Amazon', 3.8], ['Meta Platforms', 2.6], ['Alphabet A', 2.3], ['Alphabet C', 2.0], ['Broadcom', 1.8], ['Berkshire Hathaway', 1.6], ['Eli Lilly', 1.3]],
    countries: [['US', 100.0]],
    sectors: [['technology', 31.2], ['financial_services', 13.1], ['healthcare', 11.6], ['consumer_cyclical', 10.3], ['communication_services', 9.1], ['industrials', 8.6], ['consumer_defensive', 5.9], ['energy', 3.7], ['utilities', 2.5], ['basic_materials', 2.2]],
  },
}

function demoInsight(ticker: string) {
  if (demoStockTickers.includes(ticker)) {
    return { ticker, assetType: 'STOCK', composition: null }
  }
  if (demoCryptoTickers.includes(ticker)) {
    return { ticker, assetType: 'CRYPTO', composition: null }
  }
  const comp = demoEtfCompositions[ticker]
  if (comp) {
    const toSlices = (pairs: [string, number][]) => pairs.map(([label, percent]) => ({ label, percent }))
    return {
      ticker,
      assetType: 'ETF',
      composition: {
        companies: toSlices(comp.companies),
        countries: toSlices(comp.countries),
        sectors: toSlices(comp.sectors),
        source: 'Boursorama',
        asOf: new Date().toISOString().split('T')[0],
      },
    }
  }
  return { ticker, assetType: 'UNKNOWN', composition: null }
}

for (const ticker of [...demoStockTickers, ...demoCryptoTickers, ...Object.keys(demoEtfCompositions)]) {
  handlers.set(key('GET', `/securities/${ticker}/insight`), () => demoInsight(ticker))
}

// Account details: history for multiple accounts (12 months each)
function generateHistory(startBalances: number[]) {
  const now = new Date()
  const points: { id: number; date: string; balance: number }[] = []
  const months = startBalances.length

  for (let i = 0; i < months; i++) {
    // UTC for the same reason as generateNetWorthHistory: keep the ISO date on the 1st.
    const d = new Date(Date.UTC(now.getFullYear(), now.getMonth() - (months - 1 - i), 1))
    points.push({
      id: 100 + i,
      date: d.toISOString().split('T')[0],
      balance: startBalances[i],
    })
  }

  return points
}

// LEP: slow steady growth (savings account)
handlers.set(key('GET', '/accounts/1/history'), () => generateHistory(
  [6100, 6250, 6400, 6500, 6650, 6800, 6950, 7100, 7200, 7400, 7600, 7800]))

// PEA: moderate growth with some dips
handlers.set(key('GET', '/accounts/2/history'), () => generateHistory(
  [8200, 8600, 9100, 8800, 9400, 9900, 10200, 10800, 11200, 11600, 12000, 12450.5]))

// Compte Titres: more volatile
handlers.set(key('GET', '/accounts/3/history'), () => generateHistory(
  [5800, 6200, 6700, 6400, 6900, 7200, 7500, 7100, 7600, 7900, 8100, 8320.75]))

// Checking BNP: fluctuates around salary cycle
handlers.set(key('GET', '/accounts/4/history'), () => generateHistory(
  [1200, 2800, 1500, 3100, 1800, 2600, 1400, 2900, 1700, 2500, 2100, 2340.2]))

// Checking BoursoBank: smaller balance, fluctuates
handlers.set(key('GET', '/accounts/5/history'), () => generateHistory(
  [800, 1100, 950, 1300, 1050, 1200, 900, 1350, 1100, 1250, 1400, 1580.9]))

// Crypto: volatile, strong upward trend
handlers.set(key('GET', '/accounts/6/history'), () => generateHistory(
  [1800, 2100, 2400, 1900, 2600, 2800, 3100, 2700, 3400, 3600, 3900, 4250]))

// Livret A: slow steady growth
handlers.set(key('GET', '/accounts/7/history'), () => generateHistory(
  [4200, 4320, 4440, 4560, 4620, 4740, 4800, 4920, 4980, 5040, 5080, 5120]))

// Aggregate net-worth history (dashboard chart, accounts page with split=true).
// Mirrors backend NetWorthPoint: { date, total, invested, pnl, accounts? }.
function generateNetWorthHistory(months: number, accountIds: number[], split: boolean) {
  const now = new Date()
  const weights = accountIds.map((id) => mockAccounts.find((a) => a.id === id)?.currentBalanceEur ?? 1000)
  const weightSum = weights.reduce((s, w) => s + w, 0) || 1

  return Array.from({ length: months }, (_, i) => {
    // Build in UTC: a local-midnight Date run through toISOString() shifts to
    // the previous day in any timezone ahead of UTC.
    const d = new Date(Date.UTC(now.getFullYear(), now.getMonth() - (months - 1 - i), 1))
    const progress = months > 1 ? i / (months - 1) : 1
    const total = Math.round((58_000 + progress * 14_000 + Math.sin(i * 1.7) * 1_200) * 100) / 100
    const invested = Math.round(total * 0.55 * 100) / 100
    const pnl = Math.round((total * 0.06 + progress * 1_500) * 100) / 100
    const point: {
      date: string; total: number; invested: number; pnl: number
      accounts?: Record<string, { total: number; invested: number; pnl: number }>
    } = { date: d.toISOString().split('T')[0], total, invested, pnl }
    if (split) {
      point.accounts = Object.fromEntries(accountIds.map((id, idx) => {
        const share = weights[idx] / weightSum
        return [String(id), {
          total: Math.round(total * share * 100) / 100,
          invested: Math.round(invested * share * 100) / 100,
          pnl: Math.round(pnl * share * 100) / 100,
        }]
      }))
    }
    return point
  })
}

handlers.set(key('GET', '/history'), (config) => {
  const params = (config.params ?? {}) as { accountIds?: string; months?: number | string; split?: boolean | string }
  const months = Number(params.months) || 12
  const ids = String(params.accountIds ?? '').split(',').filter(Boolean).map(Number)
  const split = params.split === true || params.split === 'true'
  return generateNetWorthHistory(months, ids.length ? ids : mockAccounts.map((a) => a.id), split)
})

// PnL summary (dashboard header + account detail)
handlers.set(key('GET', '/history/pnl'), () => ({
  total: 72_000,
  invested: 39_600,
  pnl: 5_820,
  pnlPercent: 14.7,
  valueAtFrom: 66_500,
  rangePnl: 5_500,
  rangePnlPercent: 8.3,
}))

// Intraday net worth (24H dashboard range): one point per hour, mild noise.
handlers.set(key('GET', '/history/net-worth/intraday'), () => {
  const now = Date.now()
  return Array.from({ length: 24 }, (_, i) => ({
    timestamp: new Date(now - (23 - i) * 3_600_000).toISOString(),
    total: Math.round((71_400 + i * 25 + Math.sin(i / 2.5) * 180) * 100) / 100,
    invested: 39_600,
  }))
})

// Goals
handlers.set(key('GET', '/goals'), () => mockGoals)
for (let i = 1; i <= 3; i++) {
  handlers.set(key('GET', `/goals/${i}`), () => mockGoals[i - 1])
  handlers.set(key('GET', `/goals/${i}/months`), () => generateMockMonths(mockGoals[i - 1]))
  handlers.set(key('POST', `/goals/${i}/history/extend`), () => mockGoals[i - 1])
  handlers.set(key('POST', `/goals/${i}/history/extend/month`), () => mockGoals[i - 1])
}
handlers.set(key('POST', '/goals'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    ...mockGoals[0],
    id: Date.now(),
    name: body.name ?? 'New Goal',
    targetAmount: body.targetAmount ?? 0,
    deadline: body.deadline ?? '2026-01-01',
    accounts: (body.accountIds ?? []).map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    currentTotal: 0,
    percentComplete: 0,
    monthsLeft: 6,
    monthlyNeeded: 0,
    avgMonthlyContribution: null,
    isOnTrack: true,
    surplus: 0,
  }
})
for (let i = 1; i <= 3; i++) {
  handlers.set(key('PUT', `/goals/${i}`), (config) => {
    const body = JSON.parse(config.data || '{}')
    return {
      ...mockGoals[i - 1],
      name: body.name ?? mockGoals[i - 1].name,
      targetAmount: body.targetAmount ?? mockGoals[i - 1].targetAmount,
      deadline: body.deadline ?? mockGoals[i - 1].deadline,
      accounts: (body.accountIds ?? mockGoals[i - 1].accounts.map(a => a.id))
        .map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    }
  })
}
handlers.set(key('DELETE', '/goals/1'), () => null)
handlers.set(key('DELETE', '/goals/2'), () => null)
handlers.set(key('DELETE', '/goals/3'), () => null)

// Sync
handlers.set(key('GET', '/sync/status'), () => mockRequisitions)
handlers.set(key('GET', '/sync/institutions'), () => [
  { id: 'BNP_PARIBAS', name: 'BNP Paribas', bic: 'BNPAFRPP', logoUrl: null, country: 'FR' },
  { id: 'BOURSOBANK', name: 'BoursoBank', bic: 'BNPAFRPP', logoUrl: null, country: 'FR' },
])

// Crypto exchange
handlers.set(key('GET', '/crypto/exchange/status'), () => mockExchangeStatuses)

// Crypto wallet
handlers.set(key('GET', '/crypto/wallet'), () => mockWalletStatuses)

// Sync - initiate
handlers.set(key('POST', '/sync/initiate'), () => ({
  requisitionId: 'demo-req-' + Date.now(),
  authLink: 'https://demo.enablebanking.com/auth?demo=true',
}))

// Sync - complete
handlers.set(key('POST', '/sync/complete'), () => ([
  { id: 100, name: 'Demo Bank Account', type: 'CHECKING' as const, provider: 'Demo Bank', currency: 'EUR', currentBalance: 5000, currentBalanceEur: 5000, lastSyncedAt: new Date().toISOString(), isManual: false, color: '#3b82f6', ticker: null, createdAt: new Date().toISOString() }
]))

// Sync - retry
handlers.set(key('POST', '/sync/1/retry'), () => [])

// Sync - delete
handlers.set(key('DELETE', '/sync/1'), () => null)

// Trade Republic - session status
handlers.set(key('GET', '/tr/status'), () => ({ isActive: false, expiresAt: null }))

// Trade Republic - initiate auth
handlers.set(key('POST', '/tr/auth/initiate'), () => ({ processId: 'demo-tr-process' }))

// Trade Republic - complete auth
handlers.set(key('POST', '/tr/auth/complete'), () => [])

// Trade Republic - sync
handlers.set(key('POST', '/tr/sync'), () => [])

// Trade Republic - import CSV
handlers.set(key('POST', '/tr/import'), () => [])

// Trade Republic - logout
handlers.set(key('POST', '/tr/logout'), () => null)

// Crypto exchange - add
handlers.set(key('POST', '/crypto/exchange'), () => ({
  id: Date.now(), name: 'Binance', type: 'CRYPTO' as const, provider: 'BINANCE', currency: 'USDT', currentBalance: 0, currentBalanceEur: 0, lastSyncedAt: null, isManual: false, color: '#f59e0b', ticker: null, createdAt: new Date().toISOString()
}))

// Crypto exchange - sync
handlers.set(key('POST', '/crypto/exchange/1/sync'), () => [])

// Crypto exchange - remove
handlers.set(key('DELETE', '/crypto/exchange/1'), () => null)

// Crypto wallet - add
handlers.set(key('POST', '/crypto/wallet'), () => ({
  id: Date.now(), name: 'ETH Wallet', type: 'CRYPTO' as const, provider: null, currency: 'ETH', currentBalance: 0, currentBalanceEur: 0, lastSyncedAt: null, isManual: false, color: '#8b5cf6', ticker: 'ETH', createdAt: new Date().toISOString()
}))

// Crypto wallet - sync
handlers.set(key('POST', '/crypto/wallet/1/sync'), () => [])

// Crypto wallet - remove
handlers.set(key('DELETE', '/crypto/wallet/1'), () => null)

// Finary - configured
// Settings — security (2FA off in demo, one active session)
handlers.set(key('GET', '/auth/mfa/status'), () => ({
  enabled: false,
  enrolledAt: null,
  remainingRecoveryCodes: 0,
}))
handlers.set(key('GET', '/auth/sessions'), () => ([
  {
    id: 1,
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Demo Browser',
    ipPrefix: '192.168.1.x',
    createdAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
    lastUsedAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 87 * 86_400_000).toISOString(),
    trustedFor2fa: false,
    current: true,
  },
]))

// Settings — access keys (MCP). One mutable array backs list/create/revoke so
// the UI's refetch after a mutation actually reflects it (in-memory only,
// resets on reload — fine for the demo).
const demoAccessKeys: {
  id: number
  name: string
  keyPrefix: string
  scopes: string[]
  lastUsedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  createdAt: string
}[] = [
  {
    id: 1,
    name: 'Demo MCP key',
    keyPrefix: 'pk_demo_a1b2',
    scopes: ['accounts_read', 'dashboard_read'],
    lastUsedAt: new Date(Date.now() - 2 * 86_400_000).toISOString(),
    expiresAt: null,
    revokedAt: null,
    createdAt: new Date(Date.now() - 30 * 86_400_000).toISOString(),
  },
]
handlers.set(key('GET', '/access-keys'), () => [...demoAccessKeys])
handlers.set(key('POST', '/access-keys'), (config) => {
  const body = JSON.parse(config.data ?? '{}') as { name?: string; scopes?: string[]; expiresAt?: string | null }
  const newKey = {
    id: Math.max(0, ...demoAccessKeys.map((k) => k.id)) + 1,
    name: body.name ?? 'Demo key',
    keyPrefix: 'pk_demo_c3d4',
    scopes: body.scopes ?? [],
    lastUsedAt: null,
    expiresAt: body.expiresAt ?? null,
    revokedAt: null,
    createdAt: new Date().toISOString(),
  }
  demoAccessKeys.push(newKey)
  return { secret: 'pk_demo_secret_shown_once_0000000000000000', key: newKey }
})
// Routes are exact-match (no dynamic segments), so revocation is wired for the
// first few ids — enough for a demo session.
for (const id of [1, 2, 3, 4, 5]) {
  handlers.set(key('DELETE', `/access-keys/${id}`), () => {
    const k = demoAccessKeys.find((x) => x.id === id)
    if (k) k.revokedAt = new Date().toISOString()
    return null
  })
}

// Family (solo demo profile: no managed members, a small shared view)
handlers.set(key('GET', '/family/members'), () => [])
handlers.set(key('GET', '/family/dashboard'), () => ({
  sharedAccounts: [
    { id: 1, ownerName: 'Demo', name: 'LEP La Banque Postale', type: 'LEP', currency: 'EUR', balance: 7800, balanceEur: 7800 },
    { id: 2, ownerName: 'Demo', name: 'PEA Boursorama', type: 'PEA', currency: 'EUR', balance: 12450, balanceEur: 12450 },
  ],
  sharedGoals: [
    {
      id: 1,
      ownerName: 'Demo',
      name: 'Vacances été 2025',
      targetAmount: 3000,
      currentTotal: 1580.9,
      contributions: [{ memberName: 'Demo', amount: 1580.9 }],
    },
  ],
  totalSharedNetWorth: 20_250,
}))
handlers.set(key('GET', '/family/sharing'), (config) => {
  const params = (config.params ?? {}) as { resourceType?: string }
  return {
    resourceType: params.resourceType ?? 'ACCOUNT',
    sharingLevel: 'ALL',
    sharedResourceIds: [],
  }
})

handlers.set(key('GET', '/finary/configured'), () => true)

// Finary - preview file
handlers.set(key('POST', '/finary/preview'), () => ({
  accounts: [
    { finaryId: 'checking-1', finaryName: 'Compte Courant', finaryInstitution: 'BoursoBank', finaryCategory: 'checking', suggestedType: 'CHECKING' as const, currentBalance: 2500, nativeCurrency: 'EUR', transactionCount: 42 },
    { finaryId: 'pea-1', finaryName: 'PEA', finaryInstitution: 'BoursoBank', finaryCategory: 'pea', suggestedType: 'PEA' as const, currentBalance: 8000, nativeCurrency: 'EUR', transactionCount: 15 },
  ],
  existingPicsouAccounts: [],
  totalTransactionCount: 57,
  fileToken: 'demo-file-token',
}))

// Finary - import
handlers.set(key('POST', '/finary/import'), () => ({
  accountsCreated: 1,
  accountsMapped: 1,
  accountsSkipped: 0,
  snapshotsCreated: 3,
  transactionsImported: 57,
  importedAccounts: [
    { id: 100, name: 'PEA Finary', type: 'PEA' as const, currentBalance: 8000, color: '#10b981' },
  ],
}))

// Finary - API sync preview
handlers.set(key('POST', '/finary/api-sync/preview'), () => ({
  accounts: [
    { finaryId: 'checking-1', finaryName: 'Compte Courant', finaryInstitution: 'BoursoBank', finaryCategory: 'checking', suggestedType: 'CHECKING' as const, currentBalance: 2500, nativeCurrency: 'EUR', transactionCount: 42 },
  ],
  existingPicsouAccounts: [],
  totalTransactionCount: 42,
  syncToken: 'demo-sync-token',
}))

// Finary - API sync execute
handlers.set(key('POST', '/finary/api-sync/execute'), () => ({
  accountsCreated: 0,
  accountsMapped: 1,
  accountsSkipped: 0,
  snapshotsCreated: 2,
  transactionsImported: 42,
  importedAccounts: [],
}))

function generateMockMonths(goal: GoalProgress) {
  const start = new Date('2025-01-01')
  const end = new Date(goal.deadline)
  const months: { yearMonth: string; objective: number; actual: number | null; manualActual: number | null; override: number | null; effective: number | null }[] = []
  const current = new Date(start)
  const now = new Date()
  while (current <= end) {
    const ym = `${current.getFullYear()}-${String(current.getMonth() + 1).padStart(2, '0')}`
    const isPast = current <= now
    const actual = isPast ? Math.round((goal.monthlyNeeded * (0.7 + Math.random() * 0.6)) * 100) / 100 : null
    months.push({
      yearMonth: ym,
      objective: goal.monthlyNeeded,
      actual,
      manualActual: null,
      override: null,
      effective: actual,
    })
    current.setMonth(current.getMonth() + 1)
  }
  return months
}

export function createDemoAdapter() {
  return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const k = key(config.method || 'GET', config.url || '')
    const handler = handlers.get(k)

    return new Promise((resolve) => {
      setTimeout(() => {
        resolve({
          data: handler ? handler(config) : {},
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse)
      }, randomDelay())
    })
  }
}
