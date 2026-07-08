import { api } from '@/lib/api-client'
import type {
  Account,
  ExchangeType,
  ChainType,
  ExchangeStatus,
  WalletStatus,
  FinaryPreviewResponse,
  FinaryConnectionStatus,
  FinaryAccountMapping,
  FinaryImportRequest,
  FinaryImportResultResponse,
  FinaryAutoSyncResponse,
  BoursoSessionStatus,
  BoursoAuthInitResponse,
} from '@/types/api'

// --- Bank Sync (Enable Banking) ---

export const bankSyncApi = {
  searchInstitutions: (query: string) =>
    api
      .get<{ id: string; name: string; bic: string | null; logoUrl?: string | null; country: string }[]>(
        '/sync/institutions',
        { params: { query }, skipGlobalErrorRedirect: true },
      )
      .then(r => r.data),

  initiate: (institutionId: string, institutionName: string) =>
    api
      .post<{ requisitionId: string; authLink: string }>('/sync/initiate', { institutionId, institutionName })
      .then(r => r.data),

  complete: (code: string) =>
    api.post<Account[]>('/sync/complete', { code }).then(r => r.data),

  getStatus: () =>
    api
      .get<
        {
          id: number
          requisitionId: string
          institutionId: string
          institutionName: string
          status: string
          authLink: string | null
          lastSyncedAt: string | null
        }[]
      >('/sync/status')
      .then(r => r.data),

  retry: (id: number) =>
    api.post<Account[]>(`/sync/${id}/retry`).then(r => r.data),

  deleteConnection: (id: number) =>
    api.delete(`/sync/${id}`),
}

// --- Trade Republic ---

export const trApi = {
  initiateAuth: (phoneNumber: string, pin: string) =>
    api
      .post<{ processId: string }>('/tr/auth/initiate', { phoneNumber, pin })
      .then(r => r.data),

  completeAuth: (processId: string, tan: string) =>
    api
      .post<Account[]>('/tr/auth/complete', { processId, tan })
      .then(r => r.data),

  sync: () =>
    api.post<Account[]>('/tr/sync').then(r => r.data),

  getSessionStatus: () =>
    api
      .get<{ isActive: boolean; expiresAt: string | null }>('/tr/status')
      .then(r => r.data),

  importCsv: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<Account[]>('/tr/import', form).then(r => r.data)
  },

  clearSession: () =>
    api.post('/tr/logout'),
}

// --- Crypto Exchanges ---

export const cryptoExchangeApi = {
  add: (type: ExchangeType, apiKey: string, apiSecret: string) =>
    api
      .post<Account>('/crypto/exchange', { type, apiKey, apiSecret })
      .then(r => r.data),

  sync: (id: number) =>
    api.post<Account[]>(`/crypto/exchange/${id}/sync`).then(r => r.data),

  getStatuses: () =>
    api
      .get<ExchangeStatus[]>('/crypto/exchange/status')
      .then(r => r.data),

  remove: (id: number) =>
    api.delete(`/crypto/exchange/${id}`),
}

// --- Crypto Wallets ---

export const cryptoWalletApi = {
  add: (chain: ChainType, address: string, label?: string) =>
    api
      .post<Account>('/crypto/wallet', { chain, address, label })
      .then(r => r.data),

  sync: (id: number) =>
    api.post<Account[]>(`/crypto/wallet/${id}/sync`).then(r => r.data),

  list: () =>
    api
      .get<WalletStatus[]>('/crypto/wallet')
      .then(r => r.data),

  remove: (id: number) =>
    api.delete(`/crypto/wallet/${id}`),
}

// --- BoursoBank ---

export const boursoApi = {
  initiateAuth: (customerId: string, password: string) =>
    api
      .post<BoursoAuthInitResponse>('/bourso/auth/initiate', { customerId, password })
      .then(r => r.data),

  completeAuth: (processId: string, code: string) =>
    api
      .post<BoursoSessionStatus>('/bourso/auth/complete', { processId, code })
      .then(r => r.data),

  sync: () =>
    api.post<Account[]>('/bourso/sync').then(r => r.data),

  getStatus: () =>
    api.get<BoursoSessionStatus>('/bourso/status').then(r => r.data),

  clearSession: () =>
    api.delete('/bourso/session'),
}

// --- Finary ---

export const finaryApi = {
  getStatus: () =>
    api.get<FinaryConnectionStatus>('/finary/status').then(r => r.data),

  login: (email: string, password: string) =>
    api.post('/finary/login', { email, password }).then(r => r.data),

  checkTotp: () =>
    api.post<{ totpRequired: boolean }>('/finary/check-totp').then(r => r.data),

  deleteSession: () =>
    api.delete('/finary/session').then(r => r.data),

  previewFile: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return api.post<FinaryPreviewResponse>('/finary/preview', form).then(r => r.data)
  },

  previewApi: (totp?: string) =>
    api
      .post<FinaryPreviewResponse>(`/finary/api-sync/preview${totp ? `?totp=${totp}` : ''}`)
      .then(r => r.data),

  import: (request: FinaryImportRequest) =>
    api
      .post<FinaryImportResultResponse>('/finary/import', request)
      .then(r => r.data),

  executeApiSync: (syncToken: string, mappings: FinaryAccountMapping[]) =>
    api
      .post<FinaryImportResultResponse>('/finary/api-sync/execute', { syncToken, mappings })
      .then(r => r.data),

  autoSync: () =>
    api
      .post<FinaryAutoSyncResponse>('/finary/api-sync/auto')
      .then(r => r.data),
}
