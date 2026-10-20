import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type PageTree = components['schemas']['PageTreeView']
export type PageNode = components['schemas']['PageNodeView']
export type PageDoc = components['schemas']['PageView']
export type PageVersion = components['schemas']['PageVersionView']
export type PageRequest = components['schemas']['PageRequest']

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
