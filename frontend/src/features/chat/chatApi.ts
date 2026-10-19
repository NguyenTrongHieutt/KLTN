import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'

export type ChatMessage = components['schemas']['MessageView']
export type ChatPage = components['schemas']['MessagePageView']
export type Resolution = 'CHECK' | 'MASK' | 'SEND'

/** A masked match the Sensitive-content dialog lists (FR-47). */
export interface SensitiveMatch {
  kind: 'CREDENTIAL' | 'PERSONAL_DATA'
  type: string
  masked: string
}

/** The Job states with a Job Chat: from Agreement on (UX-DR17). */
const CHAT_STATES = ['AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING', 'CLOSED']

export function hasChat(state: string): boolean {
  return CHAT_STATES.includes(state)
}

const key = (jobId: string) => ['chat', jobId] as const

/** Whether the other side wrote since the viewer last read: the tab's unread dot. */
export function useJobChat(jobId: string | undefined) {
  return useQuery({
    queryKey: [...key(jobId ?? ''), 'meta'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/chat', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/**
 * The newest 50 messages first, older pages on "Xem tin cũ hơn", every page polled every 5 s while
 * the tab is open (UX-DR25).
 */
export function useChatMessages(jobId: string) {
  return useInfiniteQuery({
    queryKey: [...key(jobId), 'messages'],
    queryFn: ({ pageParam }) =>
      unwrap(
        apiClient.GET('/api/v1/jobs/{jobId}/chat/messages', {
          params: { path: { jobId }, query: { before: pageParam } },
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => (page.hasOlder && page.items.length > 0 ? page.items[0].id : undefined),
    refetchInterval: 5000,
  })
}

/** Plain text, after the FR-47 check unless the sender already chose "Che và gửi" or "Vẫn gửi". */
export function useSendMessage(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ body, sensitive }: { body: string; sensitive: Resolution }) =>
      unwrap(apiClient.POST('/api/v1/jobs/{jobId}/chat/messages', { params: { path: { jobId } }, body: { body, sensitive } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key(jobId) }),
  })
}

/** One attachment with an optional caption; the caption and the choice travel as form fields, never in the URL. */
export function useSendAttachment(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ file, body, sensitive }: { file: File; body: string; sensitive: Resolution }) => {
      const form = new FormData()
      form.append('file', file)
      form.append('body', body)
      form.append('sensitive', sensitive)
      return unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/chat/attachments', { params: { path: { jobId } }, body: formBody(form) }),
      )
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: key(jobId) }),
  })
}

/** The viewer has read the Chat; the dot goes. */
export function useMarkRead(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.POST('/api/v1/jobs/{jobId}/chat/read', { params: { path: { jobId } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...key(jobId), 'meta'] }),
  })
}
