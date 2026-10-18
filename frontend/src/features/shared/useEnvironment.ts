import { useQuery } from '@tanstack/react-query'
import { apiClient, unwrap } from '../../lib/apiClient'

/** Which environment this deployment is: `dev` or `prod` (AD-25). */
export function useEnvironment() {
  return useQuery({
    queryKey: ['shared', 'environment'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/environment')),
    staleTime: Infinity,
  })
}
