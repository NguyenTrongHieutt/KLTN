import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Product = components['schemas']['ProductView']
export type Scan = components['schemas']['ScanView']
export type Finding = components['schemas']['FindingView']
export type FindingDetail = components['schemas']['FindingDetailView']
export type RepositoryCheck = components['schemas']['RepositoryCheckView']
export type ProductRegistration = components['schemas']['ProductRegistrationRequest']
export type Tool = ProductRegistration['tools'][number]

export const PRODUCTS_QUERY_KEY = ['product'] as const

/** CUS-4 polls every 5 s while a scan is waiting, running, or about to be tried again (UX-DR21). */
export const SCAN_POLL_MS = 5_000

/** Whether the scan still has an attempt ahead of it. */
export function scanActive(scan: Scan | undefined): boolean {
  return scan?.status === 'PENDING' || scan?.status === 'RUNNING' || scan?.status === 'RETRYING'
}

/** CUS-2: the Customer's Products. */
export function useProducts() {
  return useQuery({ queryKey: [...PRODUCTS_QUERY_KEY, 'list'], queryFn: () => unwrap(apiClient.GET('/api/v1/products')) })
}

/** CUS-4: one Product, polled while its scan is active. */
export function useProduct(productId: string | undefined) {
  return useQuery({
    queryKey: [...PRODUCTS_QUERY_KEY, 'detail', productId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/products/{productId}', { params: { path: { productId: productId ?? '' } } })),
    enabled: productId !== undefined,
    retry: false,
    refetchInterval: (query) => (scanActive(query.state.data?.scan) ? SCAN_POLL_MS : false),
  })
}

/** CUS-4 "Vấn đề phát hiện": every Finding with its state, read again whenever the scan or the count changes. */
export function useFindings(product: Product) {
  return useQuery({
    queryKey: [...PRODUCTS_QUERY_KEY, 'findings', product.id, product.openFindings, product.scan?.id, product.scan?.status],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/products/{productId}/findings', { params: { path: { productId: product.id } } })),
    enabled: product.scan !== undefined,
  })
}

/** CUS-4 "Lịch sử kiểm tra": every scan run, newest first. */
export function useScanHistory(product: Product) {
  return useQuery({
    queryKey: [...PRODUCTS_QUERY_KEY, 'scans', product.id, product.scan?.id, product.scan?.status],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/products/{productId}/scans', { params: { path: { productId: product.id } } })),
    enabled: product.scan !== undefined,
  })
}

/** CUS-4 "Kiểm tra lại": both scans again, at most 5 a day. */
export function useRescan(productId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(apiClient.POST('/api/v1/products/{productId}/scans', { params: { path: { productId } } })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY_KEY }),
  })
}

/** CUS-5 "Không phải khóa thật" / "Không phải dữ liệu thật", with the required reason. */
export function useMarkFalsePositive(productId: string, findingId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (reason: string) =>
      unwrap(
        apiClient.POST('/api/v1/products/{productId}/findings/{findingId}/false-positive', {
          params: { path: { productId, findingId } },
          body: { reason },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY_KEY }),
  })
}

/** CUS-5: one Finding, with its Product's name and tools and whether the caller may resolve it. */
export function useFinding(productId: string | undefined, findingId: string | undefined) {
  return useQuery({
    queryKey: [...PRODUCTS_QUERY_KEY, 'finding', productId, findingId],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/products/{productId}/findings/{findingId}', {
          params: { path: { productId: productId ?? '', findingId: findingId ?? '' } },
        }),
      ),
    enabled: productId !== undefined && findingId !== undefined,
    retry: false,
    refetchInterval: (query) => {
      const clean = query.state.data?.cleanRepository
      return clean?.status === 'PENDING' && scanActive(clean.scan) ? SCAN_POLL_MS : false
    },
  })
}

/** CUS-5 "Đăng ký kho mã sạch": the clean repository's link, checked and then scanned in full. */
export function useRequestCleanRepository(productId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (url: string) =>
      unwrap(
        apiClient.POST('/api/v1/products/{productId}/clean-repository', {
          params: { path: { productId } },
          body: { url },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY_KEY }),
  })
}

/** CUS-5 "Tôi đã đổi khóa", after the Confirmation dialog. */
export function useConfirmRotation(productId: string, findingId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(
        apiClient.POST('/api/v1/products/{productId}/findings/{findingId}/rotation', {
          params: { path: { productId, findingId } },
        }),
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY_KEY }),
  })
}

/** CUS-4 "Xóa sản phẩm", after the destructive Confirmation dialog. */
export function useDeleteProduct(productId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(apiClient.DELETE('/api/v1/products/{productId}', { params: { path: { productId } } })),
    onSuccess: () => queryClient.removeQueries({ queryKey: [...PRODUCTS_QUERY_KEY, 'detail', productId] }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...PRODUCTS_QUERY_KEY, 'list'] }),
  })
}

/** CUS-3 step 2: every repository check, read with the Customer's own GitHub sign-in. */
export function useRepositoryCheck() {
  return useMutation({
    mutationFn: (url: string) => unwrap(apiClient.POST('/api/v1/products/repository-check', { body: { url } })),
  })
}

/** CUS-3 step 4: "Bắt đầu kiểm tra". */
export function useRegisterProduct() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ProductRegistration) => unwrap(apiClient.POST('/api/v1/products', { body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY_KEY }),
  })
}
