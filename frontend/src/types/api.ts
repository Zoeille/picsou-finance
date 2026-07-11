export type AccountType =
  | 'LEP' | 'PEA' | 'COMPTE_TITRES' | 'CRYPTO' | 'CHECKING' | 'SAVINGS'
  | 'REAL_ESTATE' | 'LOAN' | 'OTHER'

export interface RealEstateMetadata {
  purchasePrice: number
  purchaseDate: string | null
  surfaceArea: number | null
  address: string | null
  propertyType: string | null
  rentalIncome: number | null
}

export interface DebtInfo {
  linkedAccountId: number | null
  linkedAccountName: string | null
  borrowedAmount: number
  interestRate: number | null
  monthlyPayment: number | null
  lenderName: string | null
  startDate: string | null
  endDate: string | null
  insuranceMonthly: number | null
  fileFees: number | null
}

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
  logoUrl: string | null
  createdAt: string
  realEstate?: RealEstateMetadata
  debt?: DebtInfo
}

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

export interface RealEstateMetadataRequest {
  purchasePrice: number
  purchaseDate?: string
  surfaceArea?: number
  address?: string
  propertyType?: string
  rentalIncome?: number
}

export interface DebtRequest {
  linkedAccountId?: number | null
  borrowedAmount: number
  interestRate?: number
  monthlyPayment?: number
  lenderName?: string
  startDate?: string
  endDate?: string
  insuranceMonthly?: number
  fileFees?: number
}

export interface LoanInstallment {
  number: number
  date: string
  capital: number
  interest: number
  insurance: number
  totalPayment: number
  remainingBalance: number
}

export interface LoanSummary {
  totalInstallments: number
  paidInstallments: number
  remainingInstallments: number
  endDate: string | null
  monthlyPayment: number
  monthlyCapital: number
  monthlyInterest: number
  monthlyInsurance: number
  totalCost: number
  totalCapitalCost: number
  totalInterestCost: number
  totalInsuranceCost: number
  fileFees: number
  totalRepaid: number
  capitalRepaid: number
  interestRepaid: number
  insuranceRepaid: number
  remainingBalance: number
  capitalRepaidPct: number
}

export interface LoanScheduleResponse {
  summary: LoanSummary
  schedule: LoanInstallment[]
}

export interface BalanceSnapshot {
  id: number
  date: string
  balance: number
  investedAmount?: number
  createdAt?: string
}

export interface GoalProgress {
  id: number
  name: string
  targetAmount: number
  deadline: string
  createdAt: string
  historyStartMonth: string | null
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

export interface GoalMonthEntry {
  yearMonth: string
  objective: number
  actual: number | null
  manualActual: number | null
  override: number | null
  effective: number | null
}

export interface DashboardData {
  totalNetWorth: number
  totalLiabilities: number
  netWorthHistory: { date: string; total: number; invested: number; pnl: number }[]
  distribution: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: string
    hasHoldings: boolean
  }[]
  liabilities: {
    accountId: number
    name: string
    color: string
    balanceEur: number
    percentage: number
    accountType: string
    hasHoldings: boolean
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

export interface HoldingResponse {
  ticker: string
  name: string | null
  quantity: number
  averageBuyIn: number | null
  currentPrice: number | null
  currentValueEur: number | null
  costBasisEur: number | null
  pnlEur: number | null
  pnlPercent: number | null
  priceUpdatedAt: string | null
}

// --- Security insight (asset type + ETF composition) ---
export type AssetType = 'ETF' | 'STOCK' | 'CRYPTO' | 'UNKNOWN'

export interface WeightedSlice {
  label: string
  percent: number
}

export interface EtfComposition {
  companies: WeightedSlice[]
  countries: WeightedSlice[]
  sectors: WeightedSlice[]
  source: string | null
  asOf: string | null
}

export interface SecurityInsight {
  ticker: string
  assetType: AssetType
  composition: EtfComposition | null
}

export type ExchangeType = 'BINANCE' | 'KRAKEN'
export type ChainType = 'SOLANA' | 'ETHEREUM' | 'BITCOIN'
export type FinaryMappingAction = 'SKIP' | 'MAP_EXISTING' | 'CREATE_NEW'

export interface ExchangeStatus {
  id: number
  exchangeType: ExchangeType
  status: string
  lastSyncedAt: string | null
}

export interface WalletStatus {
  id: number
  chain: ChainType
  address: string
  label: string | null
  lastSyncedAt: string | null
}

export interface TrSessionStatus {
  isActive: boolean
  expiresAt: string | null
}

export interface BoursoSessionStatus {
  isActive: boolean
  expiresAt: string | null
}

export interface BoursoAuthInitResponse {
  processId: string | null
  mfaRequired: boolean
  mfaType: string | null
  contact: string | null
}

export interface FinaryAccountPreview {
  finaryId: string
  finaryName: string
  finaryInstitution: string
  finaryCategory: string
  suggestedType: AccountType
  currentBalance: number
  nativeCurrency: string
  transactionCount: number
}

export interface FinaryPreviewResponse {
  accounts: FinaryAccountPreview[]
  existingPicsouAccounts: Account[]
  totalTransactionCount: number
  fileToken: string
  autoMapped?: boolean
  suggestedMappings?: FinaryAccountMapping[]
}

export interface FinaryConnectionStatus {
  connected: boolean
  sessionId: number | null
  status: string | null
  lastSyncedAt: string | null
  maskedEmail: string | null
}

export interface NewAccountDetails {
  name: string
  type: AccountType
  provider?: string
  currency: string
  color?: string
}

export interface FinaryAccountMapping {
  finaryId: string
  finaryName: string
  finaryCategory: string
  action: FinaryMappingAction
  targetAccountId?: number
  newAccount?: NewAccountDetails
}

export interface FinaryImportRequest {
  mappings: FinaryAccountMapping[]
  fileToken: string
}

export interface ImportedAccountSummary {
  id: number
  name: string
  type: AccountType
  currentBalance: number
  color: string
}

export interface FinaryImportResultResponse {
  accountsCreated: number
  accountsMapped: number
  accountsSkipped: number
  snapshotsCreated: number
  transactionsImported: number
  importedAccounts: ImportedAccountSummary[]
}

export interface FinaryAutoSyncResponse {
  status: 'OK' | 'NEEDS_MAPPING' | 'TOTP_REQUIRED' | 'NOT_CONNECTED'
  accountsSynced: number
  newAccountCount: number
}

export interface Transaction {
  id: number
  date: string
  description: string
  amount: number
  type: string | null
  category: string | null
  nativeCurrency: string
  isManual: boolean
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker: string | null
  name: string | null
  quantity: number | null
  pricePerUnit: number | null
  fees: number | null
}

export interface TransactionRequest {
  date: string          // ISO date "YYYY-MM-DD"
  description: string
  amount: number        // signed: positive=deposit, negative=withdrawal
  txType: 'DEPOSIT' | 'WITHDRAWAL' | 'BUY' | 'SELL' | 'DIVIDEND' | 'FEE' | null
  ticker?: string
  name?: string
  quantity?: number
  pricePerUnit?: number
  currency?: string
  fees?: number         // per-trade fees, folded into the PMP cost basis
}

// --- CSV transaction import (two-phase wizard) ---

export interface CsvDialectDto {
  delimiter: string
  decimal: 'DOT' | 'COMMA'
  dateFormat: string
}

export interface ColumnMappingDto {
  date: number | null
  side: number | null
  tickerOrIsin: number | null
  name: number | null
  quantity: number | null
  unitPrice: number | null
  fees: number | null
  currency: number | null
  amount: number | null
}

export interface TransactionImportPreviewResponse {
  fileToken: string
  detectedColumns: string[]
  sampleRows: string[][]
  totalRows: number
  hasHeaderRow: boolean
  dialect: CsvDialectDto
  suggestedMapping: ColumnMappingDto
}

export interface TransactionImportRequest {
  fileToken: string
  mapping: ColumnMappingDto
  dialect: CsvDialectDto
  hasHeaderRow: boolean
  feesIncludedInAmount: boolean
  sideValueMap?: Record<string, string>
}

export interface ImportRowError {
  rowNumber: number
  message: string
}

export interface TransactionImportResultResponse {
  imported: number
  skipped: number
  errors: ImportRowError[]
}

// --- Realized P&L (closed positions) ---

export interface RealizedLot {
  ticker: string
  name: string | null
  date: string
  quantity: number
  avgCost: number
  proceeds: number
  realized: number
}

export interface TickerRealized {
  ticker: string
  name: string | null
  realized: number
  quantitySold: number
  proceeds: number
  costBasis: number
  warning: boolean
}

export interface RealizedPnlResponse {
  currency: string
  realizedTotal: number
  byTicker: TickerRealized[]
  lots: RealizedLot[]
}
