import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { createAccount, updateDebtMetadata } = vi.hoisted(() => ({
  createAccount: vi.fn(),
  updateDebtMetadata: vi.fn(),
}))

const { initiateTrAuth, completeTrAuth } = vi.hoisted(() => ({
  initiateTrAuth: vi.fn(),
  completeTrAuth: vi.fn(),
}))

vi.mock('@/features/accounts/hooks', () => ({
  useCreateAccount: () => ({ mutateAsync: createAccount, isPending: false }),
  useUpdateDebtMetadata: () => ({ mutateAsync: updateDebtMetadata, isPending: false }),
}))

vi.mock('@/features/sync/hooks', () => ({
  useSearchInstitutions: () => ({ data: undefined, isError: false, isLoading: false, error: null }),
  useInitiateBankSync: () => ({ mutate: vi.fn(), isPending: false }),
  useInitiateTrAuth: () => ({ mutate: initiateTrAuth, isPending: false }),
  useCompleteTrAuth: () => ({ mutate: completeTrAuth, isPending: false }),
  useAddCryptoExchange: () => ({ mutate: vi.fn(), isPending: false }),
  useAddCryptoWallet: () => ({ mutate: vi.fn(), isPending: false }),
  useFinaryConnectionStatus: () => ({ data: { connected: false } }),
  useFinaryLogin: () => ({ mutate: vi.fn(), isPending: false }),
  usePreviewFinaryFile: () => ({ mutate: vi.fn(), isPending: false }),
  usePreviewFinaryApi: () => ({ mutate: vi.fn(), isPending: false }),
  useImportFinary: () => ({ mutate: vi.fn(), isPending: false }),
  useExecuteFinaryApiSync: () => ({ mutate: vi.fn(), isPending: false }),
  useCheckFinaryTotp: () => ({ mutate: vi.fn(), isPending: false }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})
Object.defineProperty(document, 'elementFromPoint', {
  configurable: true,
  value: vi.fn(() => document.body),
})

const { AddAccountModal } = await import('./AddAccountModal')

function renderTradeRepublicWizard() {
  render(<AddAccountModal open onOpenChange={vi.fn()} />)
  fireEvent.click(screen.getByText('sync.tr.title'))
}

function fillPhoneAndPin() {
  fireEvent.change(screen.getByLabelText('sync.tr.phone'), {
    target: { value: '+33612345678' },
  })
  const pinInput = screen.getAllByRole('textbox').find((input) => input !== screen.getByLabelText('sync.tr.phone'))
  if (!pinInput) throw new Error('PIN input not found')
  fireEvent.change(pinInput, { target: { value: '1234' } })
}

describe('AddAccountModal Trade Republic wizard', () => {
  beforeEach(() => {
    createAccount.mockReset()
    updateDebtMetadata.mockReset()
    initiateTrAuth.mockReset()
    completeTrAuth.mockReset()
  })

  it('keeps the credentials form visible when initiation fails', async () => {
    initiateTrAuth.mockImplementation((_params, options: { onError: (error: unknown) => void }) => {
      options.onError({ response: { status: 500, data: { detail: 'PIN_INVALID' } } })
    })

    renderTradeRepublicWizard()
    fillPhoneAndPin()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidPin')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByText('sync.tr.tan')).not.toBeInTheDocument()
    expect(completeTrAuth).not.toHaveBeenCalled()
  })

  it('keeps the TAN form visible when completion fails', async () => {
    initiateTrAuth.mockImplementation((_params, options: { onSuccess: (data: { processId: string }) => void }) => {
      options.onSuccess({ processId: 'process-123' })
    })
    completeTrAuth.mockImplementation((_params, options: { onError: (error: unknown) => void }) => {
      options.onError({ response: { status: 500, data: { detail: 'VALIDATION_CODE_INVALID' } } })
    })

    renderTradeRepublicWizard()
    fillPhoneAndPin()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    const tanInput = await screen.findByLabelText('sync.tr.tan')
    fireEvent.change(tanInput, { target: { value: '9876' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidTan')).toBeInTheDocument()
    expect(screen.getByText('sync.tr.tan')).toBeInTheDocument()
    await waitFor(() => expect(tanInput).toHaveValue(''))
    expect(completeTrAuth).toHaveBeenCalledWith(
      { processId: 'process-123', tan: '9876' },
      expect.any(Object),
    )
  })
})
