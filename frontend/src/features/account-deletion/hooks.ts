import { useMutation, useQuery } from '@tanstack/react-query'
import { accountDeletionApi, type DeleteAccountRequest } from './api'

export function useDeleteMyAccount() {
  return useMutation({
    mutationFn: (req: DeleteAccountRequest) => accountDeletionApi.deleteMe(req),
  })
}

export function useAccountDeletionImpact(enabled: boolean) {
  return useQuery({
    queryKey: ['account-deletion', 'impact'],
    queryFn: accountDeletionApi.getImpact,
    enabled,
    staleTime: 0,
    gcTime: 0,
  })
}
