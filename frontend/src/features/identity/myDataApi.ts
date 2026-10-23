import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type MyData = components['schemas']['MyDataView']

const MY_DATA_QUERY_KEY = ['account', 'data'] as const

/** ALL-2: the person's deletion request and what keeps it waiting (NFR-11). */
export function useMyData() {
  return useQuery({
    queryKey: MY_DATA_QUERY_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/account/data')),
    retry: false,
  })
}

/** "Yêu cầu xóa tài khoản". */
export function useRequestDeletion() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => unwrap(apiClient.POST('/api/v1/account/deletion-request')),
    onSuccess: (data) => queryClient.setQueryData(MY_DATA_QUERY_KEY, data),
  })
}

/** Where "Tải dữ liệu của tôi" downloads from: the browser follows it with the session cookie. */
export const EXPORT_URL = '/api/v1/account/data/export'
