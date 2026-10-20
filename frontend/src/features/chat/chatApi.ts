import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, formBody, unwrap } from '@/lib/apiClient'

export type ChatMessage = components['schemas']['MessageView']
export type ChatPage = components['schemas']['MessagePageView']
export type CrewChat = components['schemas']['CrewChatView']
export type Resolution = 'CHECK' | 'MASK' | 'SEND'

/** A masked match the Sensitive-content dialog lists (FR-47). */
export interface SensitiveMatch {
  kind: 'CREDENTIAL' | 'PERSONAL_DATA'
  type: string
  masked: string
}

/**
 * Which conversation a panel shows: the Job Chat by its Job (JOB-4), or the Crew Chat by its own ID
 * (JOB-8), found first through {@link useCrewChat}.
 */
export type ChatTarget = { kind: 'job'; jobId: string } | { kind: 'crew'; jobId: string; chatId: string }

/** The Job states with a Job Chat and a Crew Chat: from Agreement on (UX-DR17). */
const CHAT_STATES = ['AGREEMENT', 'STARTING', 'ACTIVE', 'CLOSING', 'CLOSED']

export function hasChat(state: string): boolean {
  return CHAT_STATES.includes(state)
}

const key = (jobId: string) => ['chat', jobId] as const
const crewKey = (jobId: string) => ['crew-chat', jobId] as const
const targetKey = (target: ChatTarget) => (target.kind === 'job' ? key(target.jobId) : crewKey(target.jobId))

/** Whether the other side wrote since the viewer last read (the tab's unread dot), and whether it is closed. */
export function useJobChat(jobId: string | undefined) {
  return useQuery({
    queryKey: [...key(jobId ?? ''), 'meta'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/chat', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

/** The Crew Chat of a Job for its Mentor or a Crew member (FR-77): its ID, the dot, and whether it is read-only. */
export function useCrewChat(jobId: string | undefined) {
  return useQuery({
    queryKey: [...crewKey(jobId ?? ''), 'meta'],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/jobs/{jobId}/crew-chat', { params: { path: { jobId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

function readPage(target: ChatTarget, before: string | undefined) {
  return target.kind === 'job'
    ? unwrap(apiClient.GET('/api/v1/jobs/{jobId}/chat/messages', { params: { path: { jobId: target.jobId }, query: { before } } }))
    : unwrap(
        apiClient.GET('/api/v1/crew-chats/{crewChatId}/messages', {
          params: { path: { crewChatId: target.chatId }, query: { before } },
        }),
      )
}

/**
 * The newest 50 messages first, older pages on "Xem tin cũ hơn", every page polled every 5 s while
 * the tab is open (UX-DR25).
 */
export function useChatMessages(target: ChatTarget) {
  return useInfiniteQuery({
    queryKey: [...targetKey(target), 'messages'],
    queryFn: ({ pageParam }) => readPage(target, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => (page.hasOlder && page.items.length > 0 ? page.items[0].id : undefined),
    refetchInterval: 5000,
  })
}

/** Plain text, after the FR-47 check unless the sender already chose "Che và gửi" or "Vẫn gửi". */
export function useSendMessage(target: ChatTarget) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ body, sensitive }: { body: string; sensitive: Resolution }) =>
      target.kind === 'job'
        ? unwrap(
            apiClient.POST('/api/v1/jobs/{jobId}/chat/messages', {
              params: { path: { jobId: target.jobId } },
              body: { body, sensitive },
            }),
          )
        : unwrap(
            apiClient.POST('/api/v1/crew-chats/{crewChatId}/messages', {
              params: { path: { crewChatId: target.chatId } },
              body: { body, sensitive },
            }),
          ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: targetKey(target) }),
  })
}

/** One attachment with an optional caption; the caption and the choice travel as form fields, never in the URL. */
export function useSendAttachment(target: ChatTarget) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ file, body, sensitive }: { file: File; body: string; sensitive: Resolution }) => {
      const form = new FormData()
      form.append('file', file)
      form.append('body', body)
      form.append('sensitive', sensitive)
      return target.kind === 'job'
        ? unwrap(
            apiClient.POST('/api/v1/jobs/{jobId}/chat/attachments', {
              params: { path: { jobId: target.jobId } },
              body: formBody(form),
            }),
          )
        : unwrap(
            apiClient.POST('/api/v1/crew-chats/{crewChatId}/attachments', {
              params: { path: { crewChatId: target.chatId } },
              body: formBody(form),
            }),
          )
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: targetKey(target) }),
  })
}

/** The viewer has read the Chat; the dot goes. */
export function useMarkRead(target: ChatTarget) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      target.kind === 'job'
        ? unwrap(apiClient.POST('/api/v1/jobs/{jobId}/chat/read', { params: { path: { jobId: target.jobId } } }))
        : unwrap(apiClient.POST('/api/v1/crew-chats/{crewChatId}/read', { params: { path: { crewChatId: target.chatId } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...targetKey(target), 'meta'] }),
  })
}
