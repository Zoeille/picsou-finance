import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ImportTransactionsModal } from './ImportTransactionsModal'
import type { TransactionImportPreviewResponse } from '@/types/api'

const previewMutate = vi.fn()
const executeMutate = vi.fn()

vi.mock('@/features/accounts/hooks', () => ({
  usePreviewImport: () => ({ mutateAsync: previewMutate, isPending: false }),
  useExecuteImport: () => ({ mutateAsync: executeMutate, isPending: false }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: unknown) => (typeof opts === 'string' ? opts : key),
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const PREVIEW: TransactionImportPreviewResponse = {
  fileToken: 'tok-1',
  detectedColumns: ['Date', 'Sens', 'ISIN', 'Quantite', 'Cours', 'Frais'],
  sampleRows: [['15/01/2024', 'Achat', 'IE00B4L5Y983', '10', '85,20', '1,00']],
  totalRows: 1,
  hasHeaderRow: true,
  dialect: { delimiter: ';', decimal: 'COMMA', dateFormat: 'dd/MM/yyyy' },
  suggestedMapping: { date: 0, side: 1, tickerOrIsin: 2, name: null, quantity: 3, unitPrice: 4, fees: 5, currency: null, amount: null },
}

function uploadFile() {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  const file = new File(['csv'], 'trades.csv', { type: 'text/csv' })
  fireEvent.change(input, { target: { files: [file] } })
}

describe('ImportTransactionsModal', () => {
  beforeEach(() => {
    previewMutate.mockReset()
    executeMutate.mockReset()
  })

  it('previews the file then renders the detected columns for mapping', async () => {
    previewMutate.mockResolvedValue(PREVIEW)
    render(<ImportTransactionsModal open onOpenChange={vi.fn()} accountId={2} />)

    uploadFile()

    // Step 2 renders the mapping UI with the detected columns.
    expect(await screen.findByText('import.mapColumns')).toBeInTheDocument()
    expect(screen.getAllByText('ISIN').length).toBeGreaterThan(0)
    expect(previewMutate).toHaveBeenCalledOnce()
  })

  it('sends the assembled import request and shows the result', async () => {
    previewMutate.mockResolvedValue(PREVIEW)
    executeMutate.mockResolvedValue({ imported: 2, skipped: 0, errors: [] })
    render(<ImportTransactionsModal open onOpenChange={vi.fn()} accountId={2} />)

    uploadFile()
    await screen.findByText('import.mapColumns')

    fireEvent.click(screen.getByRole('button', { name: 'import.import' }))

    await waitFor(() => expect(executeMutate).toHaveBeenCalledOnce())
    expect(executeMutate).toHaveBeenCalledWith(
      expect.objectContaining({ fileToken: 'tok-1', hasHeaderRow: true, feesIncludedInAmount: false }),
    )
    expect(await screen.findByText('import.rowsImported')).toBeInTheDocument()
  })
})
