import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type PublicPerson = components['schemas']['PublicProfileView']
export type VerifiedFact = components['schemas']['VerifiedFact']
export type FactKind = VerifiedFact['kind']

/**
 * PUB-4's identity half (UX-DR96, Story 21.3), for anyone: the Verified facts in their fixed order, the typed profile,
 * the Skills by Skill Category, and the month joined; a locked person's page says only that they are not active.
 */
export function usePublicPerson(username: string | undefined) {
  return useQuery({
    queryKey: ['person', username],
    queryFn: () => unwrap(apiClient.GET('/api/v1/users/{username}', { params: { path: { username: username ?? '' } } })),
    enabled: username !== undefined,
    retry: false,
  })
}
