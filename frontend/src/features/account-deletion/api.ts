import { api } from '@/lib/api-client'

export interface DeleteAccountRequest {
  reAuth: {
    password?: string | null
    totpCode?: string | null
  }
}

export type AccountDeletionMode = 'DELETE_ACCOUNT' | 'RESET_LAST_ADMIN'

export interface AccountDeletionResponse {
  mode: AccountDeletionMode
}

export const accountDeletionApi = {
  getImpact: () =>
    api
      .get<AccountDeletionResponse>('/me/deletion-impact', { skipMemberOverride: true })
      .then(response => response.data),

  deleteMe: (req: DeleteAccountRequest) =>
    api
      .delete<AccountDeletionResponse>('/me', {
        data: req,
        skipMemberOverride: true,
      })
      .then(response => response.data),
}
