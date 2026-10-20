import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type ModerationItem = components['schemas']['ModerationItemView']
export type ContentKind = 'PRODUCT' | 'JOB_POST' | 'PAGE' | 'RATING'

const MODERATION_QUERY_KEY = ['admin', 'moderation'] as const

/** ADM-6's newest items of one kind, visible or hidden (FR-60). */
export function useModerationItems(kind: ContentKind, hidden: boolean) {
  return useQuery({
    queryKey: [...MODERATION_QUERY_KEY, kind, hidden],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/moderation', { params: { query: { kind, hidden } } })),
    retry: false,
  })
}

/** "Ẩn" with the reason the author reads; both lists of the kind refresh. */
export function useHideContent(kind: ContentKind) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ contentId, reason }: { contentId: string; reason: string }) =>
      unwrap(
        apiClient.POST('/api/v1/admin/moderation/{kind}/{contentId}/hide', {
          params: { path: { kind, contentId } },
          body: { reason },
        }),
      ),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...MODERATION_QUERY_KEY, kind] }),
  })
}
