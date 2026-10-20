import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { NOTIFICATION_QUERY_KEY } from '@/features/notification/api'
import { apiClient, unwrap } from '@/lib/apiClient'

export type JobPortfolio = components['schemas']['PortfolioView']
export type OwnEntry = components['schemas']['EntryView']

/** JOB-1's portfolio section (FR-50): the summary and the viewer's own entry. */
export function usePortfolio(jobId: string) {
  return useQuery({
    queryKey: ['portfolio', jobId],
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/portfolio', { params: { path: { boardId: jobId } } })),
    retry: false,
  })
}

type Command =
  | { action: 'draft'; text: string }
  | { action: 'approve'; text: string; showProductName: boolean }
  | { action: 'decline' }

/** The Mentor's draft, or the Customer's approval or decline; each refreshes the section and the to-dos. */
export function useSummaryCommand(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (command: Command) => {
      const path = { params: { path: { boardId: jobId } } }
      if (command.action === 'draft') {
        return unwrap(apiClient.POST('/api/v1/boards/{boardId}/portfolio/summary', { ...path, body: { text: command.text } }))
      }
      if (command.action === 'approve') {
        return unwrap(
          apiClient.POST('/api/v1/boards/{boardId}/portfolio/summary/approve', {
            ...path,
            body: { text: command.text, showProductName: command.showProductName },
          }),
        )
      }
      return unwrap(apiClient.POST('/api/v1/boards/{boardId}/portfolio/summary/decline', path))
    },
    onSuccess: async (portfolio) => {
      queryClient.setQueryData(['portfolio', jobId], portfolio)
      await queryClient.invalidateQueries({ queryKey: NOTIFICATION_QUERY_KEY })
    },
  })
}
