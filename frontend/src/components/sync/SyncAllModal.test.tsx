import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { TooltipProvider } from '@/components/ui/tooltip'

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: {
    get: apiGet,
    post: apiPost,
    delete: apiDelete,
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
}))

const { SyncAllModal } = await import('./SyncAllModal')

const TR_ACCOUNT = {
  id: 1,
  name: 'TR Titres',
  type: 'COMPTE_TITRES',
  provider: 'Trade Republic',
  currency: 'EUR',
  currentBalance: 1000,
  currentBalanceEur: 1000,
  lastSyncedAt: '2026-07-07T08:00:00Z',
  isManual: false,
  color: '#3b82f6',
  ticker: null,
  createdAt: '2026-01-01T00:00:00Z',
}

/** Routes every GET the modal's status hooks fire; TR session is inactive. */
function mockStatusEndpoints() {
  apiGet.mockImplementation((url: string) => {
    switch (url) {
      case '/sync/status':
        return Promise.resolve({ data: [] })
      case '/crypto/exchange/status':
        return Promise.resolve({ data: [] })
      case '/crypto/wallet':
        return Promise.resolve({ data: [] })
      case '/tr/status':
        return Promise.resolve({ data: { isActive: false, expiresAt: null } })
      case '/bourso/status':
        return Promise.resolve({ data: { isActive: false, expiresAt: null } })
      case '/finary/status':
        return Promise.resolve({ data: { connected: false, status: null, lastSyncedAt: null } })
      case '/accounts':
        return Promise.resolve({ data: [TR_ACCOUNT] })
      default:
        return Promise.reject(new Error(`Unexpected GET ${url}`))
    }
  })
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderModal() {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={makeClient()}>
        <TooltipProvider>{children}</TooltipProvider>
      </QueryClientProvider>
    )
  }

  render(<SyncAllModal open onOpenChange={() => {}} />, { wrapper: Wrapper })
}

/** Opens the TR inline auth form and fills phone + PIN. */
async function openTrFormAndFillCredentials() {
  // The TR row's sync button opens the inline auth form when no session is active.
  const trRow = (await screen.findByText('Trade Republic')).closest('[data-slot="card"]') as HTMLElement
  expect(trRow).not.toBeNull()
  const trSyncButton = within(trRow).getByRole('button')
  fireEvent.click(trSyncButton)

  fireEvent.change(await screen.findByLabelText('sync.tr.phone'), {
    target: { value: '+33612345678' },
  })
  fireEvent.change(screen.getByLabelText('sync.tr.pin'), {
    target: { value: '1234' },
  })
}

describe('SyncAllModal Trade Republic inline auth', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    mockStatusEndpoints()
  })

  it('shows the mapped error and stays on the phone/PIN step when initiation fails', async () => {
    apiPost.mockRejectedValue({
      response: { status: 422, data: { detail: 'PIN_INVALID' } },
    })

    renderModal()
    await openTrFormAndFillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidPin')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.tr.tan')).not.toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/initiate', {
      phoneNumber: '+33612345678',
      pin: '1234',
    })
  })

  it('shows the mapped error, keeps the TAN step and clears the code when completion fails', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') {
        return Promise.resolve({ data: { processId: 'process-123' } })
      }
      if (url === '/tr/auth/complete') {
        return Promise.reject({
          response: { status: 422, data: { detail: 'VALIDATION_CODE_INVALID' } },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderModal()
    await openTrFormAndFillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    fireEvent.change(await screen.findByLabelText('sync.tr.tan'), {
      target: { value: '9876' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidTan')).toBeInTheDocument()
    const tanInput = screen.getByLabelText('sync.tr.tan')
    expect(tanInput).toBeInTheDocument()
    await waitFor(() => expect(tanInput).toHaveValue(''))
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/complete', {
      processId: 'process-123',
      tan: '9876',
    })
  })

  it('moves to the TAN step without an error message when initiation succeeds', async () => {
    apiPost.mockResolvedValue({ data: { processId: 'process-123' } })

    renderModal()
    await openTrFormAndFillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByLabelText('sync.tr.tan')).toBeInTheDocument()
    expect(screen.queryByText(/sync\.tr\.errors\./)).not.toBeInTheDocument()
  })
})
