import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { useToasts } from '@/components/toasts'
import { apiClient, unwrap } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'

export type PageTree = components['schemas']['PageTreeView']
export type PageNode = components['schemas']['PageNodeView']
export type PageDoc = components['schemas']['PageView']
export type PageVersion = components['schemas']['PageVersionView']
export type PageRequest = components['schemas']['PageRequest']
export type Visibility = PageDoc['visibility']

const treeKey = (jobId: string) => ['pages', jobId] as const

/** The Documentation Space of a Job with a Board, as the viewer may see it; a Board's ID is its Job's. */
export function usePageTree(jobId: string | undefined) {
  return useQuery({
    queryKey: treeKey(jobId ?? ''),
    queryFn: () => unwrap(apiClient.GET('/api/v1/boards/{boardId}/pages', { params: { path: { boardId: jobId ?? '' } } })),
    enabled: jobId !== undefined,
    retry: false,
  })
}

export function usePage(pageId: string | undefined) {
  return useQuery({
    queryKey: ['page', pageId ?? ''],
    queryFn: () => unwrap(apiClient.GET('/api/v1/pages/{pageId}', { params: { path: { pageId: pageId ?? '' } } })),
    enabled: pageId !== undefined,
    retry: false,
  })
}

/** "Lịch sử phiên bản", newest first. */
export function usePageVersions(pageId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['page', pageId, 'versions'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/pages/{pageId}/versions', { params: { path: { pageId } } })),
    enabled,
    retry: false,
  })
}

/** One earlier version, read-only ("Xem"). */
export function usePageVersion(pageId: string, versionNo: number | undefined) {
  return useQuery({
    queryKey: ['page', pageId, 'versions', versionNo ?? 0],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/pages/{pageId}/versions/{versionNo}', {
          params: { path: { pageId, versionNo: versionNo ?? 0 } },
        }),
      ),
    enabled: versionNo !== undefined,
    retry: false,
  })
}

/** A new Page, or "Lưu" on an existing one (a version made on the current one). */
export function useSavePage(jobId: string, pageId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: PageRequest) =>
      pageId
        ? unwrap(apiClient.PUT('/api/v1/pages/{pageId}', { params: { path: { pageId } }, body }))
        : unwrap(apiClient.POST('/api/v1/boards/{boardId}/pages', { params: { path: { boardId: jobId } }, body })),
    onSuccess: (saved) => {
      queryClient.setQueryData(['page', saved.id], saved)
      void queryClient.invalidateQueries({ queryKey: ['page', saved.id, 'versions'] })
      return queryClient.invalidateQueries({ queryKey: treeKey(jobId) })
    },
  })
}

/** "Khôi phục": a new version from an earlier one, made on the current one. */
export function useRestorePage(jobId: string, pageId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ versionNo, version }: { versionNo: number; version: number }) =>
      unwrap(
        apiClient.POST('/api/v1/pages/{pageId}/versions/{versionNo}/restore', {
          params: { path: { pageId, versionNo } },
          body: { version },
        }),
      ),
    onSuccess: (saved) => {
      queryClient.setQueryData(['page', saved.id], saved)
      void queryClient.invalidateQueries({ queryKey: ['page', saved.id, 'versions'] })
      return queryClient.invalidateQueries({ queryKey: treeKey(jobId) })
    },
  })
}

/** "Chỉ nhóm" / "Khách hàng xem được" (FR-31): shown at once, put back with a toast when refused. */
export function useSetVisibility(jobId: string, pageId: string) {
  const queryClient = useQueryClient()
  const toasts = useToasts()
  const key = ['page', pageId]
  return useMutation({
    mutationFn: (visibility: Visibility) =>
      unwrap(apiClient.PUT('/api/v1/pages/{pageId}/visibility', { params: { path: { pageId } }, body: { visibility } })),
    onMutate: async (visibility) => {
      await queryClient.cancelQueries({ queryKey: key })
      const before = queryClient.getQueryData<PageDoc>(key)
      if (before) {
        queryClient.setQueryData<PageDoc>(key, { ...before, visibility })
      }
      return { before }
    },
    onError: (error, _visibility, context) => {
      if (context?.before) {
        queryClient.setQueryData(key, context.before)
      }
      toasts.error(problemMessage(error, { module: 'work' }).text)
    },
    onSuccess: (saved) => {
      queryClient.setQueryData(key, saved)
      return queryClient.invalidateQueries({ queryKey: treeKey(jobId) })
    },
  })
}
