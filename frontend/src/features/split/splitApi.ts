import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type SplitCard = components['schemas']['SplitCardView']
export type SplitParty = components['schemas']['SplitPartyView']
export type SplitVersion = components['schemas']['SplitVersionView']
export type SplitPreview = components['schemas']['SplitPreviewView']
export type SplitResolution = components['schemas']['SplitResolutionView']

/** One party's whole percentage, as the Mentor typed it. */
export interface SplitShare {
  partyId: string
  percent: number
}

/** How often JOB-10 reads the splits while one is open (EXPERIENCE.md's polling table). */
export const SPLIT_POLL_MS = 30_000

const splitsKey = (jobId: string) => ['split', 'cards', jobId] as const

const resolutionsKey = (splitId: string) => ['split', 'resolutions', splitId] as const

/**
 * JOB-10's cards, newest first: the Job's Mentor with no split yet reads none, and everyone but the Mentor and the
 * parties gets `NOT_FOUND`. The Job's tabs read it too, to know whether to show "Phân chia tiền"; only the page polls,
 * until each split is final and paid: `paid` follows `FINAL` from an asynchronous listener.
 */
export function useJobSplits(jobId: string | undefined, poll = false) {
  return useQuery({
    queryKey: splitsKey(jobId ?? ''),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/splits', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
    refetchInterval: (query) =>
      poll && query.state.data?.some((card) => card.status !== 'FINAL' || !card.paid) ? SPLIT_POLL_MS : false,
  })
}

/** "Gửi để xác nhận": the first draft or an edit, naming the version the card showed (0 for none). */
export function useSaveSplit(jobId: string, splitId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (body: { shownVersionNo: number; shares: SplitShare[] }) =>
      unwrap(apiClient.POST('/api/v1/splits/{splitId}/versions', { params: { path: { splitId } }, body })),
    onSettled: () => client.invalidateQueries({ queryKey: splitsKey(jobId) }),
  })
}

/** "Xác nhận": the version the card showed. */
export function useConfirmSplit(jobId: string, splitId: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: (splitVersionNo: number) =>
      unwrap(
        apiClient.POST('/api/v1/splits/{splitId}/confirmations', {
          params: { path: { splitId } },
          body: { splitVersionNo },
        }),
      ),
    onSettled: () => client.invalidateQueries({ queryKey: splitsKey(jobId) }),
  })
}

/**
 * The server's VND per party through `CrewSplit`: the frontend never computes money. It reads only for a draft that
 * passes the live checks, so `shares` is `undefined` otherwise.
 */
export function useSplitPreview(splitId: string, shares: SplitShare[] | undefined) {
  return useQuery({
    queryKey: ['split', 'preview', splitId, shares],
    queryFn: () =>
      unwrap(
        apiClient.POST('/api/v1/splits/{splitId}/preview', {
          params: { path: { splitId } },
          body: { shares: shares ?? [] },
        }),
      ),
    enabled: shares !== undefined,
    retry: false,
  })
}

/**
 * Every Split Resolution of the card's Milestone, as its parties read them: the reason, the statements, what the
 * reader may do next, and the reasoning once decided. It reads split rows, so it works after the Job ended and after
 * it is gone.
 */
export function useSplitResolutions(splitId: string, enabled: boolean, poll: boolean) {
  return useQuery({
    queryKey: resolutionsKey(splitId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/splits/{splitId}/resolutions', { params: { path: { splitId } } })),
    enabled,
    retry: false,
    refetchInterval: poll ? SPLIT_POLL_MS : false,
  })
}

/** The Milestone's open case, which holds the split and whose raiser's line reads "Đã nhờ xem xét". */
export function openCase(data: { resolutions: SplitResolution[] } | undefined): SplitResolution | undefined {
  return data?.resolutions.find((resolution) => resolution.status === 'OPEN')
}

/** What moves when a case moves: its list and the cards, which show the split's status. */
function useResolutionMoved(jobId: string, splitId: string) {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: splitsKey(jobId) }),
      client.invalidateQueries({ queryKey: resolutionsKey(splitId) }),
    ])
}

/** "Gửi yêu cầu": the reason, naming the version the card showed. */
export function useRaiseResolution(jobId: string, splitId: string) {
  const moved = useResolutionMoved(jobId, splitId)
  return useMutation({
    mutationFn: (body: { splitVersionNo: number; reason: string }) =>
      unwrap(apiClient.POST('/api/v1/splits/{splitId}/resolutions', { params: { path: { splitId } }, body })),
    onSettled: moved,
  })
}

/** "Gửi ý kiến": one statement of a party other than the raiser. */
export function useResolutionStatement(jobId: string, splitId: string, resolutionId: string) {
  const moved = useResolutionMoved(jobId, splitId)
  return useMutation({
    mutationFn: (text: string) =>
      unwrap(
        apiClient.POST('/api/v1/split-resolutions/{resolutionId}/statements', {
          params: { path: { resolutionId } },
          body: { text },
        }),
      ),
    onSettled: moved,
  })
}

/** "Rút yêu cầu": the raiser's, before a decision. */
export function useWithdrawResolution(jobId: string, splitId: string, resolutionId: string) {
  const moved = useResolutionMoved(jobId, splitId)
  return useMutation({
    mutationFn: () =>
      unwrap(
        apiClient.POST('/api/v1/split-resolutions/{resolutionId}/withdraw', { params: { path: { resolutionId } } }),
      ),
    onSettled: moved,
  })
}
