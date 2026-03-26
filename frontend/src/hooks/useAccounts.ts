import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { accountsApi, type AccountRequest } from '../lib/api'

export const ACCOUNTS_KEY = ['accounts'] as const

export function useAccounts() {
  return useQuery({
    queryKey: ACCOUNTS_KEY,
    queryFn: accountsApi.list,
  })
}

export function useAccount(id: number) {
  return useQuery({
    queryKey: [...ACCOUNTS_KEY, id],
    queryFn: () => accountsApi.get(id),
  })
}

export function useAccountHistory(id: number, from?: string, to?: string) {
  return useQuery({
    queryKey: [...ACCOUNTS_KEY, id, 'history', from, to],
    queryFn: () => accountsApi.history(id, from, to),
    enabled: !!id,
  })
}

export function useCreateAccount() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: AccountRequest) => accountsApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
  })
}

export function useUpdateAccount() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: AccountRequest }) => accountsApi.update(id, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ACCOUNTS_KEY })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useDeleteAccount() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => accountsApi.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ACCOUNTS_KEY })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}

export function useAddSnapshot() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, balance, date }: { id: number; balance: number; date: string }) =>
      accountsApi.addSnapshot(id, balance, date),
    onSuccess: (_data, vars) => {
      qc.invalidateQueries({ queryKey: [...ACCOUNTS_KEY, vars.id] })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
    },
  })
}
