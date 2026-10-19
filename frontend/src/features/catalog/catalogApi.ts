import { useQuery } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type CatalogView = components['schemas']['CatalogView']

export type CatalogSkill = components['schemas']['CatalogSkill']

export const CATALOG_QUERY_KEY = ['catalog'] as const

/**
 * The active Skill catalog (AD-38), loaded once per visit: it changes only when an Administrator
 * edits it, and the browser revalidates it with its ETag.
 */
export function useCatalog() {
  return useQuery({
    queryKey: CATALOG_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/catalog')),
    staleTime: Infinity,
  })
}
