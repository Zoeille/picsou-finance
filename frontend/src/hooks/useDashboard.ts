import { useQuery } from '@tanstack/react-query'
import { dashboardApi } from '../lib/api'

export function useDashboard() {
  return useQuery({
    queryKey: ['dashboard'],
    queryFn: dashboardApi.get,
    refetchInterval: 5 * 60_000, // refresh every 5 minutes
  })
}
